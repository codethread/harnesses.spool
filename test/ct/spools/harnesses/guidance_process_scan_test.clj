(ns ct.spools.harnesses.guidance-process-scan-test
  "Scanner backpressure and retained cleanup identity regressions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.guidance-process :as process]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity]
            [ct.spools.harnesses.internal.guidance-process-scan :as scan]
            [ct.spools.harnesses.internal.strict-json :as strict-json])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- capability-document []
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" "codex"
   "adapter-contract" "native-v1"
   "adapter-sha256" (str/join (repeat 64 "a"))
   "executable-sha256" (capability/file-sha256 "/usr/bin/true")
   "host-version" "0.154.0"
   "launch-profile-sha256" (str/join (repeat 64 "b"))
   "max-context-bytes" 3072
   "hook-fact"
   {"eventName" "sessionStart"
    "key" "managed-guidance"
    "source" "plugin"
    "sourcePath" "/fixture/plugin/hooks.json"
    "pluginId" "agents-fixture"
    "command" "node managed-guidance.js"
    "enabled" true
    "trustStatus" "trusted"
    "currentHash" "trusted-current-hash"
    "timeoutSec" 15
    "additionalContextLimit" 4096}})

(defn- helper-source [root]
  (str "import fs from 'node:fs';\n"
       "fs.writeFileSync(" (strict-json/canonical-json
                            (str (io/file root "helper.pid")))
       ", String(process.pid));\n"
       "process.stdin.resume();\n"
       "process.stdin.on('end', () => {\n"
       "  process.stdout.write('{}');\n"
       "});\n"))

(defn- scanner-source [root mode]
  (let [failure-mode (if (map? mode) (:mode mode) mode)
        fail-at (when (map? mode) (:fail-at mode))
        anchor (str (io/file root "anchor.pid"))
        counter (str (io/file root "scanner.count"))
        flood-count (if (contains? #{:stdout-overflow :stderr-overflow}
                                   failure-mode)
                      80000
                      20000)]
    (str "#!/bin/sh\n"
         "if [ \"$1\" = \"-o\" ]; then\n"
         "  printf '%s' \"$4\" > " (pr-str anchor) "\n"
         "  exec /bin/ps \"$@\"\n"
         "fi\n"
         (when fail-at
           (str "count=0\n"
                "if [ -f " (pr-str counter) " ]; then "
                "read -r count < " (pr-str counter) "; fi\n"
                "count=$((count + 1))\n"
                "printf '%s' \"$count\" > " (pr-str counter) "\n"
                "if [ \"$count\" -ne \"" fail-at "\" ]; then\n"
                "  exec /bin/ps \"$@\"\n"
                "fi\n"))
         (case failure-mode
           :finite-flood
           (str "/usr/bin/awk 'BEGIN { for (i=0; i<" flood-count
                "; i++) print \"scanner\" > \"/dev/stderr\" }'\n"
                "exec /bin/ps \"$@\"\n")
           :stdout-overflow
           (str "/usr/bin/awk 'BEGIN { for (i=0; i<" flood-count
                "; i++) print \"1 1\" }'\n"
                "exec /bin/ps \"$@\"\n")
           :stderr-overflow
           (str "/usr/bin/awk 'BEGIN { for (i=0; i<" flood-count
                "; i++) print \"scanner\" > \"/dev/stderr\" }'\n"
                "exec /bin/ps \"$@\"\n")
           :stalled "while :; do :; done\n"
           :exited "printf '1 1\\n'; exit 0\n"
           :nonzero "printf 'scanner failed\\n' >&2; exit 7\n"
           :malformed "printf 'not-a-process-row\\n'; exit 0\n"
           :invalid-utf8 "printf '\\377'; exit 0\n"
           :duplicate "printf '1 1\\n1 1\\n'; exit 0\n"))))

(defn- finalize-profile [profile]
  (assoc-in profile [:process-ownership :reviewed-closure-sha256]
            (closure/reviewed-sha256 profile)))

(defn- with-scanner-profile [mode f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "guidance-scanner"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        scripts (doto (io/file root "scripts") .mkdirs)
        entrypoint (io/file scripts "managed-guidance-preflight.mjs")
        scanner (io/file root "scanner.sh")
        environment (-> (into {} (System/getenv))
                        (dissoc "NODE_OPTIONS" "NODE_PATH" "OPENSSL_CONF"
                                "DYLD_INSERT_LIBRARIES"))
        interpreter (capability/resolve-executable "node" environment)
        _ (spit entrypoint (helper-source root))
        _ (spit scanner (scanner-source root mode))
        _ (.setExecutable scanner true)
        profile
        (finalize-profile
         {:harness "codex"
          :preflight {:path (.getCanonicalPath entrypoint)
                      :sha256 (closure/file-sha256 entrypoint)}
          :capability (capability-document)
          :executable-closure
          {:schema "millstrand.local-guidance-executable-closure/v1"
           :reviewed-complete true
           :resolver-policy (closure/resolver-policy)
           :artifacts
           [(closure/artifact "entrypoint" entrypoint)
            (closure/artifact "interpreter" interpreter)
            (closure/artifact "ownership-scanner" scanner)
            (closure/artifact "subprocess" "/bin/sh")
            (closure/artifact "subprocess" "/bin/ps")
            (closure/artifact "subprocess" "/usr/bin/awk")]
           :resolution-inputs
           {:cwd (.getCanonicalPath root)
            :environment (closure/resolution-environment environment)}}
          :process-ownership
          {:contract "private-posix-session/inherited-process-group-v1"
           :reviewed-closure-sha256 (str/join (repeat 64 "0"))
           :child-process-behavior "inherited-process-group-only"}})]
    (try
      (f {:root root
          :profile (assoc profile :effective-environment environment)})
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn- thread-count [thread-name]
  (count (filter #(= thread-name (.getName ^Thread %))
                 (keys (Thread/getAllStackTraces)))))

(defn- await-uninterruptibly! [^CountDownLatch latch]
  (loop []
    (when-not (try
                (.await latch)
                true
                (catch InterruptedException _ false))
      (recur))))

(defn- workers-retired? []
  (loop [remaining 200]
    (if (zero? (thread-count "guidance-admission-worker"))
      true
      (if (zero? remaining)
        false
        (do (Thread/sleep 10) (recur (dec remaining)))))))

(defn- process-for-root? [root]
  (with-open [handles (ProcessHandle/allProcesses)]
    (boolean
     (some #(some-> (.commandLine (.info ^ProcessHandle %))
                    (.orElse "")
                    (str/includes? (.getCanonicalPath root)))
           (iterator-seq (.iterator handles))))))

(defn- run-profile
  ([profile]
   (run-profile profile nil))
  ([profile budget]
   (let [started (System/nanoTime)]
     (try
       {:result (if budget
                  (process/run! profile "{}" budget clojure.core/identity)
                  (process/run! profile "{}"))
        :elapsed-millis (/ (- (System/nanoTime) started) 1000000.0)}
       (catch Throwable error
         {:error error
          :elapsed-millis (/ (- (System/nanoTime) started) 1000000.0)})))))

(defn- cleanup-phase-budget []
  (let [started-at (System/nanoTime)]
    {:started-at started-at
     :work-deadline (+ started-at (.toNanos TimeUnit/SECONDS 8))
     :deadline (+ started-at (.toNanos TimeUnit/SECONDS 10))}))

(defn- pid-from [file]
  (when (.isFile file)
    (parse-long (str/trim (slurp file)))))

(defn- alive-pid? [pid]
  (boolean (and pid
                (some-> (ProcessHandle/of (long pid))
                        (.orElse nil)
                        .isAlive))))

(defn- start-sleep! []
  (.start (ProcessBuilder. ^java.util.List ["/bin/sleep" "30"])))

(defn- stop! [process]
  (when (.isAlive process)
    (.destroyForcibly process))
  (.waitFor process 5 TimeUnit/SECONDS))

(deftest completed-direct-scanner-does-not-require-a-live-birth-observation
  (with-scanner-profile
    :exited
    (fn [{:keys [root profile]}]
      (let [original-direct identity/retain-direct
            completed (atom nil)
            sentinel (start-sleep!)
            deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 3))]
        (try
          (let [rows
                (with-redefs
                 [identity/retain-direct
                  (fn [handle role destroy!]
                    (let [retained (original-direct handle role destroy!)]
                      ;; Force the legal fast-exit interleaving without a sleep.
                      (while (.isAlive ^ProcessHandle handle)
                        (when-not (< (System/nanoTime) deadline)
                          (throw (ex-info "Fixture scanner did not exit" {})))
                        (Thread/yield))
                      (reset! completed retained)
                      retained))]
                  (scan/scan! (dissoc profile :effective-environment)
                              (:effective-environment profile) root
                              (str (io/file root "scanner.sh")) deadline
                              #(- % (System/nanoTime))))]
            (is (= [{:pid 1 :pgid 1}] rows))
            (is (:direct? @completed))
            (is (not (identity/live? @completed)))
            (is (.isAlive sentinel)))
          (finally
            (stop! sentinel)))))))

(deftest live-scanner-with-unavailable-birth-still-fails-and-is-cleaned
  (with-scanner-profile
    :stalled
    (fn [{:keys [profile]}]
      (let [original-retain identity/retain
            original-direct identity/retain-direct
            scanner (atom nil)
            sentinel (start-sleep!)]
        (try
          (let [{:keys [error]}
                (with-redefs
                 [identity/retain-direct
                  (fn [& args]
                    (let [retained (apply original-direct args)]
                      (when (= "direct-ownership-scanner" (:role retained))
                        (reset! scanner retained))
                      retained))
                  identity/retain
                  (fn [handle role]
                    (when (= "ownership-scanner" role)
                      (is (.isAlive ^ProcessHandle handle))
                      (throw (ex-info "Fixture live scanner birth unavailable" {})))
                    (original-retain handle role))]
                  (run-profile profile))]
            (is (= "Fixture live scanner birth unavailable" (ex-message error)))
            (is @scanner)
            (is (not (identity/live? @scanner)))
            (is (.isAlive sentinel)))
          (finally
            (stop! sentinel)))))))

(deftest scanner-drains-finite-flood-without-starving-helper-io
  (with-scanner-profile
    :finite-flood
    (fn [{:keys [root profile]}]
      (let [before-helper (thread-count "guidance-preflight-io")
            before-scanner (thread-count "guidance-preflight-scan-io")
            {:keys [result error elapsed-millis]} (run-profile profile)]
        (is (nil? error))
        (is (zero? (:exit-code result)))
        (is (< elapsed-millis 3000.0))
        (is (false? (process-for-root? root)))
        (is (= before-helper (thread-count "guidance-preflight-io")))
        (is (= before-scanner (thread-count "guidance-preflight-scan-io")))))))

(deftest first-and-confirming-cleanup-scanner-failures-preserve-custody
  (doseq [[mode message]
          [[:stdout-overflow #"exceeded its byte limit"]
           [:stderr-overflow #"exceeded its byte limit"]
           [:stalled #"scan timed out"]
           [:nonzero #"ownership scan failed"]
           [:malformed #"scan output is malformed"]]
          [phase fail-at] [[:first 3] [:confirming 4]]]
    (testing (str (name phase) " " (name mode))
      (with-scanner-profile
        {:mode mode :fail-at fail-at}
        (fn [{:keys [root profile]}]
          (let [unrelated (start-sleep!)]
            (try
              (let [{:keys [error]} (run-profile profile
                                                 (cleanup-phase-budget))
                    anchor-pid (pid-from (io/file root "anchor.pid"))
                    helper-pid (pid-from (io/file root "helper.pid"))]
                (is (re-find message (ex-message error)))
                (is (not (alive-pid? anchor-pid)))
                (is (not (alive-pid? helper-pid)))
                (is (.isAlive unrelated))
                (is (false? (process-for-root? root))))
              (finally
                (stop! unrelated)))))))))

(deftest scanner-birth-observation-shares-the-admission-deadline
  (with-scanner-profile
    :stalled
    (fn [{:keys [root profile]}]
      (let [original-retain identity/retain
            original-direct identity/retain-direct
            entered (CountDownLatch. 1)
            release (CountDownLatch. 1)
            scanner (atom nil)
            unrelated (start-sleep!)]
        (try
          (let [{:keys [error elapsed-millis]}
                (with-redefs
                 [identity/retain-direct
                  (fn [& args]
                    (let [retained (apply original-direct args)]
                      (when (= "direct-ownership-scanner" (:role retained))
                        (reset! scanner retained))
                      retained))
                  identity/retain
                  (fn [handle role]
                    (when (= "ownership-scanner" role)
                      (.countDown entered)
                      (await-uninterruptibly! release))
                    (original-retain handle role))]
                  (run-profile profile))
                anchor-pid (pid-from (io/file root "anchor.pid"))]
            (is (zero? (.getCount entered)))
            (is (re-find #"Guidance preflight timed out" (ex-message error)))
            (is (< elapsed-millis 3200.0))
            (is @scanner)
            (is (not (identity/live? @scanner)))
            (is (not (alive-pid? anchor-pid)))
            (is (.isAlive unrelated))
            (is (false? (process-for-root? root))))
          (finally
            (.countDown release)
            (is (workers-retired?))
            (stop! unrelated)))))))

(deftest delayed-scanner-liveness-cannot-block-revocation-or-signal-late
  (with-scanner-profile
    :stalled
    (fn [{:keys [root profile]}]
      (let [original-live? identity/live?
            delayed? (atom false)
            {:keys [error elapsed-millis]}
            (with-redefs [identity/live?
                          (fn [retained]
                            (when (and (contains? #{"direct-ownership-scanner"
                                                    "ownership-scanner"}
                                                  (:role retained))
                                       (compare-and-set! delayed? false true))
                              (try
                                (Thread/sleep 3100)
                                (catch InterruptedException _ nil)))
                            (original-live? retained))]
              (run-profile profile))
            anchor-pid (pid-from (io/file root "anchor.pid"))
            helper-pid (pid-from (io/file root "helper.pid"))]
        (is @delayed?)
        (is (re-find #"scan timed out" (ex-message error)))
        (is (< elapsed-millis 3000.0))
        (is (not (alive-pid? anchor-pid)))
        (is (not (alive-pid? helper-pid)))
        (is (false? (process-for-root? root)))
        (is (zero? (thread-count "guidance-admission-worker")))))))

(deftest scanner-failures-remain-bounded-and-clean-retained-identities
  (doseq [[mode message]
          [[:stdout-overflow #"exceeded its byte limit"]
           [:stderr-overflow #"exceeded its byte limit"]
           [:stalled #"scan timed out"]
           [:nonzero #"ownership scan failed"]
           [:malformed #"scan output is malformed"]
           [:invalid-utf8 #"not valid UTF-8"]
           [:duplicate #"duplicate PIDs"]]]
    (testing (name mode)
      (with-scanner-profile
        mode
        (fn [{:keys [root profile]}]
          (let [unrelated (.start (ProcessBuilder.
                                   ^java.util.List ["/bin/sleep" "30"]))]
            (try
              (let [before-helper (thread-count "guidance-preflight-io")
                    before-scanner
                    (thread-count "guidance-preflight-scan-io")
                    {:keys [error elapsed-millis]} (run-profile profile)
                    anchor-pid (pid-from (io/file root "anchor.pid"))
                    helper-pid (pid-from (io/file root "helper.pid"))]
                (is (re-find message (ex-message error)))
                (is (< elapsed-millis 3000.0))
                (is (not (alive-pid? anchor-pid)))
                (is (not (alive-pid? helper-pid)))
                (is (.isAlive unrelated))
                (is (false? (process-for-root? root)))
                (is (= before-helper
                       (thread-count "guidance-preflight-io")))
                (is (= before-scanner
                       (thread-count "guidance-preflight-scan-io"))))
              (finally
                (when (.isAlive unrelated) (.destroyForcibly unrelated))
                (.waitFor unrelated 5 TimeUnit/SECONDS)))))))))

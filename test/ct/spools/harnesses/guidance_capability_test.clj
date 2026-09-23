(ns ct.spools.harnesses.guidance-capability-test
  "Exact disposable capability admission tests for disabled native guidance."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.guidance-process :as guidance-process]
            [ct.spools.harnesses.internal.strict-json :as strict-json])
  (:import [java.lang ProcessHandle]))

(defn- codex-hook []
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
   "additionalContextLimit" 4096})

(defn- pi-hook []
  {"host-package" "@earendil-works/pi-coding-agent"
   "host-package-version" "0.84.4"
   "host-package-sha256" (str/join (repeat 64 "c"))
   "extensions"
   [{"entrypoint" "/fixture/system-prompt/index.ts"
     "closure-sha256" (str/join (repeat 64 "d"))}]
   "prompt-owner-entrypoint" "/fixture/system-prompt/index.ts"
   "system-prompt-options-contract" "owned-v1"})

(defn- capability-document [harness executable-sha]
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" harness
   "adapter-contract" "native-v1"
   "adapter-sha256" (str/join (repeat 64 "a"))
   "executable-sha256" executable-sha
   "host-version" (if (= "codex" harness) "0.154.0" "0.84.4")
   "launch-profile-sha256" (str/join (repeat 64 "b"))
   "max-context-bytes" (if (= "codex" harness) 3072 65536)
   "hook-fact" (if (= "codex" harness) (codex-hook) (pi-hook))})

(defn- finalize-profile [profile]
  (assoc-in profile [:process-ownership :reviewed-closure-sha256]
            (capability/process-ownership-sha256 profile)))

(defn- local-profile [harness root preflight document environment artifacts]
  (finalize-profile
   {:harness harness
    :preflight {:path (.getCanonicalPath preflight)
                :sha256 (capability/file-sha256 preflight)}
    :capability document
    :executable-closure
    {:schema "millstrand.local-guidance-executable-closure/v1"
     :reviewed-complete true
     :resolver-policy (closure/resolver-policy)
     :artifacts (vec artifacts)
     :resolution-inputs
     {:cwd (.getCanonicalPath root)
      :environment (closure/resolution-environment environment)}}
    :process-ownership
    {:contract "private-posix-session/inherited-process-group-v1"
     :reviewed-closure-sha256 (str/join (repeat 64 "0"))
     :child-process-behavior "inherited-process-group-only"}}))

(defn- replace-artifact [profile role file]
  (let [artifacts (get-in profile [:executable-closure :artifacts])
        index (first (keep-indexed #(when (= role (:role %2)) %1)
                                   artifacts))]
    (finalize-profile
     (-> (cond-> profile
           (= "entrypoint" role)
           (assoc :preflight
                  {:path (.getCanonicalPath (io/file file))
                   :sha256 (capability/file-sha256 file)}))
         (update-in [:executable-closure :artifacts]
                    assoc index (closure/artifact role file))))))

(defn- with-profile [harness f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "guidance-capability"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        scripts (doto (java.io.File. root "scripts") .mkdirs)
        preflight (java.io.File. scripts "managed-guidance-preflight.mjs")
        _ (spit preflight "// exact disposable preflight\n")
        executable "/usr/bin/true"
        environment {}
        document (capability-document
                  harness (capability/file-sha256 executable))
        interpreter (capability/resolve-executable "node" (System/getenv))
        scanner (.getCanonicalPath (io/file "/bin/ps"))
        profile
        (local-profile
         harness root preflight document environment
         [(closure/artifact "entrypoint" preflight)
          (closure/artifact "interpreter" interpreter)
          (closure/artifact "subprocess" "/bin/sh")
          (closure/artifact "subprocess" "/bin/sleep")
          (closure/artifact "ownership-scanner" scanner)])
        request {"harness" harness
                 "executable" executable
                 "mode" "headless"
                 "cwd" (.getCanonicalPath root)
                 "workspace" (.getCanonicalPath root)
                 "env" environment
                 "extra-argv" []
                 "resumes" false}]
    (try
      (f {:profile profile :document document :request request})
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn- result-json [document]
  (strict-json/canonical-json
   {"schema" "millstrand.agent-guidance-preflight/v1"
    "result" "capable"
    "capability" document}))

(defn- process-result [profile stdout]
  {:source (:preflight profile)
   :reviewed-closure-sha256
   (get-in profile [:process-ownership :reviewed-closure-sha256])
   :exit-code 0
   :stdout stdout
   :stderr ""})

(deftest exact-codex-and-pi-test-profiles-are-admitted
  (let [expected-wire-digests
        {"codex" "f5ebc972f9c4d99ef21bb2c5e48fe8aaf2872413220323a72c0957d2fea8f33e"
         "pi" "5571967d168852e6a02baef2ab63e02ca63be55a0b6a71b5cb7aba4d4f7c1eca"}
        fixed-executable-sha (str/join (repeat 64 "e"))]
    (doseq [harness ["codex" "pi"]]
      (with-profile
        harness
        (fn [{:keys [profile document request]}]
          (let [serialized-request (atom nil)]
            (binding [capability/*test-capability-profiles* [profile]
                      capability/*test-preflight-runner*
                      (fn [accepted request-json]
                        (reset! serialized-request
                                (strict-json/parse-object!
                                 request-json 65536 "serialized request"))
                        (process-result accepted (result-json document)))]
              (is (= document (capability/preflight! request)))
              (is (= (get expected-wire-digests harness)
                     (strict-json/canonical-sha256
                      (capability-document harness fixed-executable-sha))))
              (is (not (contains? document "process-ownership")))
              (is (not (contains? document "executable-closure")))
              (is (= (assoc request
                            "schema"
                            "millstrand.agent-guidance-preflight/v1")
                     @serialized-request))
              (is (= "/usr/bin/true"
                     (get @serialized-request "executable"))))))))))

(deftest malformed-changed-nonzero-and-legacy-required-evidence-fails-loudly
  (with-profile
    "codex"
    (fn [{:keys [profile document request]}]
      (doseq [[label process]
              [["malformed" (process-result profile "{nope}")]
               ["unicode-lookalike"
                (process-result profile "{\"schema\":\"\\u００４１\"}")]
               ["duplicate-key"
                (process-result
                 profile
                 (str "{\"schema\":\"millstrand.agent-guidance-preflight/v1\","
                      "\"result\":\"capable\",\"result\":\"capable\","
                      "\"capability\":{}}"))]
               ["trailing-comma"
                (process-result
                 profile
                 (let [json (result-json document)]
                   (str (subs json 0 (dec (count json))) ",}")))]
               ["changed"
                (process-result
                 profile
                 (result-json (assoc document "host-version" "0.154.1")))]
               ["wire-process-ownership"
                (process-result
                 profile
                 (result-json
                  (assoc document "process-ownership" {"contract" "local"})))]
               ["unknown-wire-key"
                (process-result profile
                                (result-json (assoc document "unknown" true)))]
               ["oversized"
                (process-result profile
                                (str "{\"padding\":\""
                                     (str/join (repeat 70000 "x")) "\"}"))]
               ["nonzero" (assoc (process-result profile "")
                                 :exit-code 2 :stderr "probe failed")]
               ["duplicate-injector"
                (process-result
                 profile
                 (strict-json/canonical-json
                  {"schema" "millstrand.agent-guidance-preflight/v1"
                   "result" "legacy-required"
                   "code" "duplicate-injector"
                   "diagnostic" "two effective registrations"}))]]]
        (testing label
          (binding [capability/*test-capability-profiles* [profile]
                    capability/*test-preflight-runner* (fn [_ _] process)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (capability/preflight! request)))))))))

(defn- actual-preflight-failure [profile request source]
  (let [path (get-in profile [:preflight :path])
        _ (spit path source)
        profile (replace-artifact profile "entrypoint" path)
        started (System/nanoTime)
        failure (binding [capability/*test-capability-profiles* [profile]]
                  (try
                    (capability/preflight! request)
                    nil
                    (catch Throwable error error)))]
    {:failure failure
     :elapsed-millis (/ (- (System/nanoTime) started) 1000000.0)}))

(deftest exact-preflight-command-bounds-process-and-pipe-lifetimes
  (with-profile
    "codex"
    (fn [{:keys [profile request]}]
      (doseq [[label source request]
              [["sleeping root"
                "setTimeout(() => {}, 10000);\n"
                request]
               ["blocked request input"
                "setTimeout(() => {}, 10000);\n"
                (assoc-in request ["env" "PADDING"]
                          (str/join (repeat 64000 "x")))]
               ["descendant holding pipes"
                (str "const {spawn} = await import('node:child_process');\n"
                     "spawn(process.execPath, "
                     "['-e', 'setTimeout(() => {}, 10000)'], "
                     "{stdio: ['ignore', 'inherit', 'inherit']});\n"
                     "setTimeout(() => process.exit(0), 100);\n")
                request]
               ["flooded output"
                (str "process.stdout.write('x'.repeat(200000));\n"
                     "setTimeout(() => {}, 10000);\n")
                request]]]
        (testing label
          (let [{:keys [failure elapsed-millis]}
                (actual-preflight-failure profile request source)]
            (is (instance? Throwable failure))
            (is (< elapsed-millis 4500.0))
            (case label
              "flooded output"
              (is (re-find #"output exceeded" (ex-message failure)))

              "descendant holding pipes"
              (is (re-find #"JSON value is missing" (ex-message failure)))

              (is (re-find #"timed out" (ex-message failure))))))))))

(deftest untrusted-and-duplicate-approved-sources-are-rejected
  (with-profile
    "codex"
    (fn [{:keys [profile document request]}]
      (let [untrusted (assoc-in document ["hook-fact" "trustStatus"]
                                "bypassed")
            untrusted-profile
            (finalize-profile (assoc profile :capability untrusted))
            untrusted (:capability untrusted-profile)]
        (binding [capability/*test-capability-profiles* [untrusted-profile]
                  capability/*test-preflight-runner*
                  (fn [accepted _]
                    (process-result accepted (result-json untrusted)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"not trusted"
                                (capability/preflight! request))))
        (binding [capability/*test-capability-profiles* [profile profile]
                  capability/*test-preflight-runner*
                  (fn [accepted _]
                    (process-result accepted (result-json document)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"duplicate accepted"
                                (capability/preflight! request))))))))

(defn- alive-pid? [pid]
  (some-> (ProcessHandle/of (long pid)) (.orElse nil) .isAlive))

(defn- await-pid-file [file]
  (loop [remaining 1000]
    (if (.isFile file)
      (parse-long (str/trim (slurp file)))
      (if (pos? remaining)
        (do (Thread/sleep 5) (recur (- remaining 5)))
        (throw (ex-info "Fixture child PID was not recorded"
                        {:file (str file)}))))))

(deftest private-supervisor-records-and-reaps-exact-process-identities
  (with-profile
    "codex"
    (fn [{:keys [profile]}]
      (let [path (get-in profile [:preflight :path])
            normal-source
            (str "let input = '';\n"
                 "process.stdin.setEncoding('utf8');\n"
                 "process.stdin.on('data', chunk => input += chunk);\n"
                 "process.stdin.on('end', () => {"
                 "process.stdout.write(input);"
                 "process.stderr.write('fixture-stderr');});\n")
            _ (spit path normal-source)
            profile (assoc (replace-artifact profile "entrypoint" path)
                           :effective-environment {})]
        (dotimes [_ 5]
          (let [process-result
                (guidance-process/run! profile "{\"probe\":true}")
                pids (vals (:owned-pids process-result))]
            (is (= [0 "{\"probe\":true}" "fixture-stderr"]
                   [(:exit-code process-result)
                    (:stdout process-result)
                    (:stderr process-result)]))
            (is (= 3 (count pids)))
            (is (every? pos-int? pids))
            (is (= (count pids) (count (set pids))))
            (is (not-any? alive-pid? pids))))))))

(deftest private-supervisor-reaps-immediately-orphaned-process-groups-only
  (with-profile
    "codex"
    (fn [{:keys [profile request]}]
      (let [root (.getParentFile
                  (.getParentFile
                   (io/file (get-in profile [:preflight :path]))))
            unrelated (.start
                       (ProcessBuilder.
                        ^java.util.List
                        ["node" "-e" "setInterval(() => {}, 10000)"]))]
        (try
          (doseq [[label pid-name source]
                  [["immediate root exit"
                    "child.pid"
                    (str "const {spawn} = await import('node:child_process');\n"
                         "const fs = await import('node:fs');\n"
                         "const child = spawn(process.execPath, "
                         "['-e', 'setInterval(() => {}, 10000)'], "
                         "{stdio: ['ignore', 'inherit', 'inherit']});\n"
                         "fs.writeFileSync(new URL('../child.pid', import.meta.url), "
                         "String(child.pid));\n"
                         "child.unref();\n"
                         "process.exit(0);\n")]
                   ["immediate root and intermediate exits"
                    "intermediate-child.pid"
                    (str "const {spawn} = await import('node:child_process');\n"
                         "const fs = await import('node:fs');\n"
                         "const leafFile = new URL('../intermediate-child.pid', "
                         "import.meta.url).pathname;\n"
                         "const code = `const {spawn} = require('node:child_process');"
                         "const fs = require('node:fs');"
                         "const leaf = spawn(process.execPath, "
                         "['-e', 'setInterval(() => {}, 10000)'], "
                         "{stdio: ['ignore', 'inherit', 'inherit']});"
                         "fs.writeFileSync(process.argv[1], String(leaf.pid));"
                         "leaf.unref();process.exit(0);`;\n"
                         "const intermediate = spawn(process.execPath, "
                         "['-e', code, leafFile], "
                         "{stdio: ['ignore', 'inherit', 'inherit']});\n"
                         "intermediate.unref();\n"
                         "const wait = setInterval(() => {"
                         "if (fs.existsSync(leafFile)) {"
                         "clearInterval(wait);process.exit(0);}}, 5);\n")]]]
            (testing label
              (let [pid-file (io/file root pid-name)
                    {:keys [failure elapsed-millis]}
                    (actual-preflight-failure profile request source)
                    child-pid (await-pid-file pid-file)]
                (is (re-find #"JSON value is missing" (ex-message failure)))
                (is (< elapsed-millis 4500.0))
                (is (not (alive-pid? child-pid)))
                (is (.isAlive unrelated)))))
          (finally
            (.destroyForcibly unrelated)
            (.waitFor unrelated 2 java.util.concurrent.TimeUnit/SECONDS)))))))

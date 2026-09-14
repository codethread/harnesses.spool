(ns ct.spools.harnesses.internal.guidance-process
  "Privately owned, bounded subprocess transport for guidance preflight."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.io ByteArrayOutputStream]
           [java.lang ProcessHandle]
           [java.nio.charset CharacterCodingException CodingErrorAction
            StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util UUID]
           [java.util.concurrent Callable ExecutionException Executors Future
            ThreadFactory TimeUnit TimeoutException]))

(def ^:private capture-limit (* 64 1024))
(def ^:private state-limit (* 8 1024))
(def ^:private timeout-millis 3000)
(def ^:private cleanup-millis 400)

(def ^:private supervisor-source
  (str/join
   "\n"
   ["import fs from 'node:fs';"
    "import { spawn } from 'node:child_process';"
    "const [anchor, root, scanner, entrypoint, state, bootReady, go, token] = process.argv.slice(2);"
    "const writeState = value => {"
    "  const temporary = `${state}.tmp-${process.pid}`;"
    "  fs.writeFileSync(temporary, JSON.stringify(value), { mode: 0o600 });"
    "  fs.renameSync(temporary, state);"
    "};"
    "const child = spawn(process.execPath, [anchor, root, scanner, entrypoint, state, bootReady, go, token], {"
    "  detached: true,"
    "  stdio: ['inherit', 'inherit', 'inherit']"
    "});"
    "writeState({ token, phase: 'boot', anchorPid: child.pid });"
    "fs.writeFileSync(`${bootReady}.tmp`, token, { mode: 0o600 });"
    "fs.renameSync(`${bootReady}.tmp`, bootReady);"
    "child.unref();"
    "while (!fs.existsSync(go)) await new Promise(resolve => setTimeout(resolve, 1));"
    "fs.closeSync(0);"
    "fs.closeSync(1);"
    "fs.closeSync(2);"
    "setInterval(() => {}, 60000);"]))

(def ^:private anchor-source
  (str/join
   "\n"
   ["import fs from 'node:fs';"
    "import { spawn, spawnSync } from 'node:child_process';"
    "const [root, scanner, entrypoint, state, bootReady, go, token] = process.argv.slice(2);"
    "const writeState = value => {"
    "  const temporary = `${state}.tmp-${process.pid}`;"
    "  fs.writeFileSync(temporary, JSON.stringify(value), { mode: 0o600 });"
    "  fs.renameSync(temporary, state);"
    "};"
    "const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));"
    "while (!fs.existsSync(bootReady)) await delay(1);"
    "if (fs.readFileSync(bootReady, 'utf8') !== token) process.exit(70);"
    "const inspected = spawnSync(scanner, ['-o', 'pgid=', '-p', String(process.pid)], {"
    "  encoding: 'utf8',"
    "  timeout: 250"
    "});"
    "const pgid = Number((inspected.stdout || '').trim());"
    "if (inspected.status !== 0 || pgid !== process.pid) {"
    "  writeState({ token, phase: 'ownership-failed', anchorPid: process.pid });"
    "  setInterval(() => {}, 60000);"
    "} else {"
    "  writeState({ token, phase: 'owned', anchorPid: process.pid, pgid });"
    "  while (!fs.existsSync(go)) await delay(1);"
    "  if (fs.readFileSync(go, 'utf8') !== token) process.exit(71);"
    "  const child = spawn(process.execPath, [entrypoint], {"
    "    cwd: root,"
    "    stdio: ['inherit', 'inherit', 'inherit']"
    "  });"
    "  writeState({ token, phase: 'running', anchorPid: process.pid, pgid, helperPid: child.pid });"
    "  fs.closeSync(0);"
    "  fs.closeSync(1);"
    "  fs.closeSync(2);"
    "  child.on('error', error => writeState({"
    "    token, phase: 'launch-failed', anchorPid: process.pid, pgid,"
    "    helperPid: child.pid, diagnostic: error.message"
    "  }));"
    "  child.on('exit', (code, signal) => writeState({"
    "    token, phase: 'finished', anchorPid: process.pid, pgid,"
    "    helperPid: child.pid, exitCode: code, signal"
    "  }));"
    "  setInterval(() => {}, 60000);"
    "}"]))

(defn- remaining-nanos [deadline]
  (- deadline (System/nanoTime)))

(defn- timed-out! [phase]
  (fail! "Guidance preflight timed out"
         {:timeout-millis timeout-millis :phase phase}))

(defn- await-future! [^Future future deadline phase]
  (let [remaining (remaining-nanos deadline)]
    (when-not (pos? remaining)
      (timed-out! phase))
    (try
      (.get future remaining TimeUnit/NANOSECONDS)
      (catch TimeoutException _
        (timed-out! phase))
      (catch ExecutionException error
        (throw (.getCause error)))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (fail! "Guidance preflight was interrupted" {:phase phase})))))

(defn- decode-utf8 [^bytes bytes label]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (java.nio.ByteBuffer/wrap bytes)))
    (catch CharacterCodingException _
      (fail! (str label " is not valid UTF-8") {}))))

(defn- capture! [input]
  (let [output (ByteArrayOutputStream.)
        buffer (byte-array 4096)]
    (loop [total 0]
      (let [count (.read input buffer)]
        (if (neg? count)
          (.toByteArray output)
          (let [next-total (+ total count)]
            (when (> next-total capture-limit)
              (fail! "Guidance preflight output exceeded its byte limit"
                     {:max-bytes capture-limit}))
            (.write output buffer 0 count)
            (recur next-total)))))))

(defn- daemon-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "guidance-preflight-io")
        (.setDaemon true)))))

(defn- private-directory! []
  (let [path (Files/createTempDirectory "harness-guidance-preflight-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/setPosixFilePermissions
       path (PosixFilePermissions/fromString "rwx------"))
      path
      (catch Throwable error
        (Files/deleteIfExists path)
        (fail! "Guidance preflight cannot establish a private supervisor directory"
               {:cause (ex-message error)})))))

(defn- write-file! [^Path path value]
  (Files/write path (.getBytes ^String value StandardCharsets/UTF_8)
               (make-array java.nio.file.OpenOption 0))
  path)

(defn- write-signal! [^Path path value]
  (let [temporary (.resolveSibling path
                                   (str (.getFileName path) ".tmp-"
                                        (UUID/randomUUID)))]
    (write-file! temporary value)
    (Files/move temporary path
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    path))

(defn- state-document [^Path state-path]
  (when (Files/exists state-path (make-array java.nio.file.LinkOption 0))
    (strict-json/parse-object! (Files/readString state-path)
                               state-limit
                               "Guidance preflight supervisor state")))

(defn- valid-pid? [value]
  (and (integer? value) (pos? value)))

(defn- validate-state! [state token]
  (when-not (= token (get state "token"))
    (fail! "Guidance preflight supervisor identity does not match" {}))
  (when-not (valid-pid? (get state "anchorPid"))
    (fail! "Guidance preflight supervisor recorded an invalid PID" {}))
  state)

(defn- inspect-futures! [futures deadline]
  (doseq [[phase ^Future future] futures
          :when (.isDone future)]
    (await-future! future deadline phase)))

(defn- await-owned! [state-path token futures deadline ownership]
  (loop []
    (inspect-futures! futures deadline)
    (when-not (pos? (remaining-nanos deadline))
      (timed-out! "ownership"))
    (if-let [state (some-> (state-document state-path)
                           (validate-state! token))]
      (let [phase (get state "phase")
            anchor-pid (get state "anchorPid")]
        (reset! ownership {:anchor-pid anchor-pid})
        (case phase
          "ownership-failed"
          (fail! "Guidance preflight could not establish private process ownership"
                 {:anchor-pid anchor-pid})
          "owned"
          (if (= anchor-pid (get state "pgid"))
            (do
              (swap! ownership assoc :pgid anchor-pid)
              state)
            (fail! "Guidance preflight process group identity is invalid" state))
          (do (Thread/sleep 1) (recur))))
      (do (Thread/sleep 1) (recur)))))

(defn- release-supervisor! [process deadline]
  (when (.isAlive process)
    (.destroyForcibly process))
  (let [remaining (remaining-nanos deadline)]
    (when-not (and (pos? remaining)
                   (.waitFor process remaining TimeUnit/NANOSECONDS))
      (timed-out! "supervisor-retirement"))))

(defn- await-helper! [state-path token futures deadline ownership]
  (loop []
    (inspect-futures! futures deadline)
    (when-not (pos? (remaining-nanos deadline))
      (timed-out! "process-completion"))
    (if-let [state (some-> (state-document state-path)
                           (validate-state! token))]
      (let [phase (get state "phase")]
        (when-let [helper-pid (get state "helperPid")]
          (when-not (valid-pid? helper-pid)
            (fail! "Guidance preflight helper recorded an invalid PID" state))
          (swap! ownership assoc :helper-pid helper-pid))
        (case phase
          "launch-failed"
          (fail! "Guidance preflight helper could not be executed"
                 {:diagnostic (get state "diagnostic")})
          "finished" state
          (do (Thread/sleep 1) (recur))))
      (do (Thread/sleep 1) (recur)))))

(defn- process-handle [pid]
  (when pid
    (.orElse (ProcessHandle/of (long pid)) nil)))

(defn- run-ps! [scanner deadline]
  (let [process (.start (ProcessBuilder. ^java.util.List
                         [scanner "-axo" "pid=,pgid="]))
        remaining (remaining-nanos deadline)]
    (when-not (and (pos? remaining)
                   (.waitFor process remaining TimeUnit/NANOSECONDS))
      (.destroyForcibly process)
      (timed-out! "cleanup-process-scan"))
    (when-not (zero? (.exitValue process))
      (fail! "Guidance preflight process ownership scan failed" {}))
    (slurp (.getInputStream process))))

(defn- group-pids [scanner pgid deadline]
  (->> (str/split-lines (run-ps! scanner deadline))
       (keep (fn [line]
               (let [[pid group] (str/split (str/trim line) #"\s+")]
                 (when (and pid group (= (str pgid) group))
                   (parse-long pid)))))
       set))

(defn- signal-exact! [pid signalled]
  (when-let [^ProcessHandle handle (process-handle pid)]
    (when (.isAlive handle)
      (swap! signalled conj pid)
      (.destroyForcibly handle))))

(defn- cleanup-owned! [process ownership executor streams scanner deadline]
  (let [{:keys [anchor-pid pgid]} @ownership
        signalled (atom #{})]
    (if pgid
      (loop []
        (when-not (pos? (remaining-nanos deadline))
          (timed-out! "owned-process-cleanup"))
        (let [members (group-pids scanner pgid deadline)
              live-members (filter #(some-> (process-handle %) .isAlive)
                                   members)
              descendants (remove #{anchor-pid} live-members)
              anchor-live? (some #{anchor-pid} live-members)]
          (cond
            (seq descendants)
            (do
              (doseq [pid descendants]
                (signal-exact! pid signalled))
              (Thread/sleep 1)
              (recur))

            anchor-live?
            (signal-exact! anchor-pid signalled))))
      (signal-exact! anchor-pid signalled))
    (signal-exact! (.pid process) signalled)
    (while (some #(some-> (process-handle %) .isAlive) @signalled)
      (when-not (pos? (remaining-nanos deadline))
        (timed-out! "owned-process-join"))
      (Thread/sleep 1))
    (doseq [stream streams]
      (try (.close stream) (catch Exception _ nil)))
    (.shutdownNow executor)
    (let [remaining (remaining-nanos deadline)]
      (when-not (pos? remaining)
        (timed-out! "io-worker-cleanup"))
      (when-not (.awaitTermination executor remaining TimeUnit/NANOSECONDS)
        (timed-out! "io-worker-cleanup")))
    @signalled))

(defn- delete-directory! [^Path directory]
  (doseq [file (reverse (file-seq (.toFile directory)))]
    (Files/deleteIfExists (.toPath file))))

(defn run!
  "Run the exact preflight helper inside a private, identity-fenced process group."
  [profile request-json]
  (let [{:keys [path]} (:preflight profile)
        process-environment (:effective-environment profile)
        reviewed-profile (dissoc profile :effective-environment)
        script (io/file path)
        scripts-dir (.getParentFile script)
        root (.getParentFile scripts-dir)]
    (when-not (and (= "scripts" (.getName scripts-dir))
                   (= "managed-guidance-preflight.mjs" (.getName script)))
      (fail! "Guidance preflight must use scripts/managed-guidance-preflight.mjs"
             {:path path}))
    (let [deadline (+ (System/nanoTime)
                      (.toNanos TimeUnit/MILLISECONDS timeout-millis))
          execution-deadline (- deadline
                                (.toNanos TimeUnit/MILLISECONDS cleanup-millis))
          directory (private-directory!)
          supervisor-path (.resolve directory "supervisor.mjs")
          anchor-path (.resolve directory "anchor.mjs")
          state-path (.resolve directory "state.json")
          boot-path (.resolve directory "boot.ready")
          go-path (.resolve directory "go.ready")
          token (str (UUID/randomUUID))
          ownership (atom {})
          executor (Executors/newFixedThreadPool 3 (daemon-thread-factory))
          budget! #(when-not (pos? (remaining-nanos execution-deadline))
                     (timed-out! "closure-verification"))
          interpreter (closure/artifact-path reviewed-profile "interpreter")
          scanner (closure/artifact-path reviewed-profile "ownership-scanner")
          entrypoint (closure/artifact-path reviewed-profile "entrypoint")]
      (try
        (closure/verify! reviewed-profile process-environment budget!)
        (write-file! supervisor-path supervisor-source)
        (write-file! anchor-path anchor-source)
        (let [builder
              (doto
               (ProcessBuilder.
                ^java.util.List
                [interpreter (str supervisor-path) (str anchor-path)
                 (.getCanonicalPath root) scanner entrypoint (str state-path)
                 (str boot-path) (str go-path) token])
                (.directory root))
              _ (doto (.environment builder)
                  (.clear)
                  (.putAll process-environment))
              process (.start builder)
              input (.submit
                     executor
                     ^Callable
                     #(with-open [stream (.getOutputStream process)]
                        (.write stream
                                (.getBytes ^String request-json
                                           StandardCharsets/UTF_8))))
              stdout (.submit executor
                              ^Callable #(capture! (.getInputStream process)))
              stderr (.submit executor
                              ^Callable #(capture! (.getErrorStream process)))
              futures [["request-input" input]
                       ["stdout-drain" stdout]
                       ["stderr-drain" stderr]]
              streams [(.getOutputStream process)
                       (.getInputStream process)
                       (.getErrorStream process)]]
          (try
            (await-owned! state-path token futures execution-deadline ownership)
            (when-not (.isAlive process)
              (fail! "Guidance preflight supervisor failed"
                     {:exit-code (.exitValue process)}))
            (when-not (some-> (process-handle (:anchor-pid @ownership)) .isAlive)
              (fail! "Guidance preflight ownership anchor is not live" @ownership))
            (closure/verify! reviewed-profile process-environment budget!)
            (write-signal! go-path token)
            (let [finished (await-helper! state-path token futures
                                          execution-deadline ownership)]
              (release-supervisor! process execution-deadline)
              (await-future! input execution-deadline "request-input")
              (let [stdout-bytes (await-future! stdout execution-deadline
                                                "stdout-drain")
                    stderr-bytes (await-future! stderr execution-deadline
                                                "stderr-drain")
                    closure-check
                    (.submit executor
                             ^Callable
                             #(closure/verify! reviewed-profile
                                               process-environment budget!))]
                {:source (:preflight reviewed-profile)
                 :reviewed-closure-sha256
                 (await-future! closure-check execution-deadline
                                "closure-recheck")
                 :exit-code (or (get finished "exitCode") 1)
                 :stdout (decode-utf8 stdout-bytes
                                      "Guidance preflight stdout")
                 :stderr (decode-utf8 stderr-bytes
                                      "Guidance preflight stderr")
                 :owned-pids {:supervisor-pid (.pid process)
                              :anchor-pid (:anchor-pid @ownership)
                              :helper-pid (:helper-pid @ownership)}}))
            (finally
              (try
                (cleanup-owned! process ownership executor streams scanner
                                deadline)
                (finally
                  (when (.isAlive process)
                    (.destroyForcibly process))
                  (doseq [stream streams]
                    (try (.close stream) (catch Exception _ nil)))
                  (.shutdownNow executor))))))
        (finally
          (.shutdownNow executor)
          (delete-directory! directory))))))

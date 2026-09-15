(ns ct.spools.harnesses.internal.guidance-process
  "Privately owned, bounded subprocess transport for guidance preflight."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-authority :as authority]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline]
            [ct.spools.harnesses.internal.guidance-process-cleanup :as cleanup]
            [ct.spools.harnesses.internal.guidance-process-gate :as gate]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity]
            [ct.spools.harnesses.internal.guidance-process-retirement :as retirement]
            [ct.spools.harnesses.internal.guidance-process-scan :as scan]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset CharacterCodingException CodingErrorAction
            StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util UUID]
           [java.util.concurrent Callable Executors Future ThreadFactory]))

(def ^:private capture-limit (* 64 1024))
(def ^:private state-limit (* 8 1024))
(def ^:private supervisor-source
  (str/join
   "\n"
   ["import fs from 'node:fs';"
    "import { spawn } from 'node:child_process';"
    "const [supervisorReady, anchor, gate, root, scanner, entrypoint, state, bootReady, go, helperReady, token] = process.argv.slice(2);"
    "const writeState = value => {"
    "  const temporary = `${state}.tmp-${process.pid}`;"
    "  fs.writeFileSync(temporary, JSON.stringify(value), { mode: 0o600 });"
    "  fs.renameSync(temporary, state);"
    "};"
    "while (!fs.existsSync(supervisorReady)) await new Promise(resolve => setTimeout(resolve, 1));"
    "if (fs.readFileSync(supervisorReady, 'utf8') !== token) process.exit(69);"
    "const child = spawn(process.execPath, [anchor, gate, root, scanner, entrypoint, state, bootReady, go, helperReady, token], {"
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
    "const [gate, root, scanner, entrypoint, state, bootReady, go, helperReady, token] = process.argv.slice(2);"
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
    "  timeout: 500"
    "});"
    "const pgid = Number((inspected.stdout || '').trim());"
    "if (inspected.status !== 0 || pgid !== process.pid) {"
    "  writeState({ token, phase: 'ownership-failed', anchorPid: process.pid });"
    "  setInterval(() => {}, 60000);"
    "} else {"
    "  writeState({ token, phase: 'owned', anchorPid: process.pid, pgid });"
    "  while (!fs.existsSync(go)) await delay(1);"
    "  if (fs.readFileSync(go, 'utf8') !== token) process.exit(71);"
    "  const child = spawn(process.execPath, [gate, entrypoint, helperReady, token], {"
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

(def ^:private helper-gate-source
  (str/join
   "\n"
   ["import fs from 'node:fs';"
    "import { pathToFileURL } from 'node:url';"
    "const [entrypoint, helperReady, token] = process.argv.slice(2);"
    "const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));"
    "while (!fs.existsSync(helperReady)) await delay(1);"
    "if (fs.readFileSync(helperReady, 'utf8') !== token) process.exit(73);"
    "process.argv[1] = entrypoint;"
    "await import(pathToFileURL(entrypoint).href);"]))

(defn- remaining-nanos [deadline]
  (- deadline (System/nanoTime)))

(defn- timed-out! [phase]
  (deadline/timed-out! phase))

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
    (deadline/await-future! future deadline phase)))

(defn- await-owned! [state-path token futures deadline]
  (loop []
    (inspect-futures! futures deadline)
    (when-not (pos? (remaining-nanos deadline))
      (timed-out! "ownership"))
    (if-let [state (some-> (state-document state-path)
                           (validate-state! token))]
      (let [phase (get state "phase")
            anchor-pid (get state "anchorPid")]
        (case phase
          "ownership-failed"
          (fail! "Guidance preflight could not establish private process ownership"
                 {:anchor-pid anchor-pid})
          "owned"
          (if (= anchor-pid (get state "pgid"))
            state
            (fail! "Guidance preflight process group identity is invalid" state))
          (do (Thread/sleep 1) (recur))))
      (do (Thread/sleep 1) (recur)))))

(defn- await-helper-started! [state-path token futures deadline]
  (loop []
    (inspect-futures! futures deadline)
    (when-not (pos? (remaining-nanos deadline))
      (timed-out! "helper-start"))
    (if-let [state (some-> (state-document state-path)
                           (validate-state! token))]
      (case (get state "phase")
        "launch-failed"
        (fail! "Guidance preflight helper could not be executed"
               {:diagnostic (get state "diagnostic")})
        "running"
        (let [helper-pid (get state "helperPid")]
          (when-not (valid-pid? helper-pid)
            (fail! "Guidance preflight helper recorded an invalid PID" state))
          state)
        "finished"
        (fail! "Guidance preflight helper ran before identity retention" {})
        (do (Thread/sleep 1) (recur)))
      (do (Thread/sleep 1) (recur)))))

(defn- await-helper! [state-path token futures deadline ownership]
  (loop []
    (inspect-futures! futures deadline)
    (when-not (pos? (remaining-nanos deadline))
      (timed-out! "process-completion"))
    (if-let [state (some-> (state-document state-path)
                           (validate-state! token))]
      (let [phase (get state "phase")
            helper-pid (get state "helperPid")]
        (when (and helper-pid
                   (not= helper-pid (:pid (:helper @ownership))))
          (fail! "Guidance preflight helper identity changed" state))
        (case phase
          "launch-failed"
          (fail! "Guidance preflight helper could not be executed"
                 {:diagnostic (get state "diagnostic")})
          "finished" state
          (do (Thread/sleep 1) (recur))))
      (do (Thread/sleep 1) (recur)))))

(defn- delete-directory! [^Path directory]
  (doseq [file (reverse (file-seq (.toFile directory)))]
    (Files/deleteIfExists (.toPath file))))

(defn- retain-child-bounded!
  [budget ownership key parent-key pid role]
  (deadline/owned!
   budget (str role "-identity")
   (fn [operation-authority]
     (let [parent (get @ownership parent-key)]
       (try
         (identity/retain-child!
          parent pid role
          #(authority/run!
            operation-authority (str role "-promotion")
            (fn [] (swap! ownership assoc key %))))
         (catch Throwable error
           (let [{:keys [errors]}
                 (identity/retain-children!
                  parent "proven-child-for-cleanup"
                  #(authority/run!
                    operation-authority "cleanup-child-promotion"
                    (fn []
                      (swap! ownership update :proven-children
                             (fnil conj []) %))))]
             (doseq [cleanup-error errors]
               (.addSuppressed error cleanup-error)))
           (throw error)))))))

(defn run!
  "Run the exact preflight helper inside a private, identity-fenced process group."
  ([profile request-json]
   (run! profile request-json (deadline/start) (fn [result] result)))
  ([profile request-json budget validate-result!]
   (deadline/check! budget "process-setup")
   (let [{:keys [path]} (:preflight profile)
         process-environment (:effective-environment profile)
         reviewed-profile (dissoc profile :effective-environment)
         script (io/file path)
         scripts-dir (.getParentFile script)
         root (.getParentFile scripts-dir)
         operation-deadline (deadline/deadline budget)
         execution-deadline (deadline/work-deadline budget)
         directory (atom nil)
         executor (atom nil)
         process (atom nil)
         streams (atom [])
         ownership (atom {})
         interpreter (closure/artifact-path reviewed-profile "interpreter")
         scanner (closure/artifact-path reviewed-profile "ownership-scanner")
         entrypoint (closure/artifact-path reviewed-profile "entrypoint")
         cleanup-started? (atom false)
         cleanup-process!
         #(when (and @process
                     (compare-and-set! cleanup-started? false true))
            (cleanup/cleanup-owned!
             ownership @executor @streams reviewed-profile
             process-environment root scanner operation-deadline
             remaining-nanos))
         budget (assoc budget :on-revoked cleanup-process!)
         budget! #(deadline/check! budget "closure-verification")]
     (when-not (and (= "scripts" (.getName scripts-dir))
                    (= "managed-guidance-preflight.mjs" (.getName script)))
       (fail! "Guidance preflight must use scripts/managed-guidance-preflight.mjs"
              {:path path}))
     (cleanup/complete!
      (fn []
        (deadline/check! budget "process-profile-validation")
        (let [private-path (private-directory!)
              _ (reset! directory private-path)
              supervisor-path (.resolve private-path "supervisor.mjs")
              anchor-path (.resolve private-path "anchor.mjs")
              gate-path (.resolve private-path "helper-gate.mjs")
              state-path (.resolve private-path "state.json")
              supervisor-ready-path (.resolve private-path "supervisor.ready")
              boot-path (.resolve private-path "boot.ready")
              go-path (.resolve private-path "go.ready")
              helper-path (.resolve private-path "helper.ready")
              token (str (UUID/randomUUID))
              io-executor (Executors/newFixedThreadPool
                           3 (daemon-thread-factory))
              _ (reset! executor io-executor)]
          (deadline/bounded!
           budget "process-closure-verification"
           #(closure/verify! reviewed-profile process-environment budget!))
          (write-file! supervisor-path supervisor-source)
          (write-file! anchor-path anchor-source)
          (write-file! gate-path helper-gate-source)
          (let [builder
                (doto
                 (ProcessBuilder.
                  ^java.util.List
                  [interpreter (str supervisor-path)
                   (str supervisor-ready-path) (str anchor-path)
                   (str gate-path) (.getCanonicalPath root) scanner entrypoint
                   (str state-path) (str boot-path) (str go-path)
                   (str helper-path) token])
                  (.directory root))
                _ (doto (.environment builder)
                    (.clear)
                    (.putAll process-environment))
                supervisor-process
                (deadline/owned!
                 budget "supervisor-start"
                 (fn [operation-authority]
                   (authority/run!
                    operation-authority "supervisor-start"
                    #(let [started (.start builder)
                           _ (reset! process started)
                           handle (.toHandle started)
                           direct (identity/retain-direct
                                   handle "direct-supervisor"
                                   (fn [] (.destroyForcibly started)))]
                       (swap! ownership assoc :direct-supervisor direct)
                       started))))
                supervisor-handle (.toHandle supervisor-process)
                supervisor
                (deadline/bounded!
                 budget "supervisor-identity"
                 #(identity/retain supervisor-handle "supervisor"))
                _ (swap! ownership #(-> %
                                        (assoc :supervisor supervisor)
                                        (dissoc :direct-supervisor)))
                _ (gate/publish! budget supervisor-ready-path token
                                 "supervisor-release")
                input-stream (.getOutputStream supervisor-process)
                _ (swap! streams conj input-stream)
                output-stream (.getInputStream supervisor-process)
                _ (swap! streams conj output-stream)
                error-stream (.getErrorStream supervisor-process)
                _ (swap! streams conj error-stream)
                input (.submit
                       io-executor
                       ^Callable
                       #(with-open [stream input-stream]
                          (.write stream
                                  (.getBytes ^String request-json
                                             StandardCharsets/UTF_8))))
                stdout (.submit io-executor
                                ^Callable #(capture! output-stream))
                stderr (.submit io-executor
                                ^Callable #(capture! error-stream))
                futures [["request-input" input]
                         ["stdout-drain" stdout]
                         ["stderr-drain" stderr]]]
            (deadline/check! budget "supervisor-start")
            (let [owned (await-owned! state-path token futures
                                      execution-deadline)
                  anchor (retain-child-bounded!
                          budget ownership :anchor :supervisor
                          (get owned "anchorPid") "ownership-anchor")
                  pgid (get owned "pgid")
                  rows (scan/scan! reviewed-profile process-environment root
                                   scanner execution-deadline remaining-nanos)]
              (deadline/bounded!
               budget "anchor-correlation"
               #(identity/correlate! anchor [] rows pgid))
              (swap! ownership assoc :pgid pgid))
            (when-not (.isAlive supervisor-process)
              (fail! "Guidance preflight supervisor failed"
                     {:exit-code (.exitValue supervisor-process)}))
            (deadline/bounded!
             budget "anchor-liveness"
             #(identity/require-live!
               (:anchor @ownership)
               "Guidance preflight ownership anchor is not live"))
            (gate/publish! budget go-path token "helper-start")
            (let [started (await-helper-started! state-path token futures
                                                 execution-deadline)
                  helper (retain-child-bounded!
                          budget ownership :helper :anchor
                          (get started "helperPid") "preflight-helper")
                  {:keys [anchor pgid]} @ownership
                  rows (scan/scan! reviewed-profile process-environment root
                                   scanner execution-deadline remaining-nanos)]
              (deadline/bounded!
               budget "helper-correlation"
               #(identity/correlate! anchor [helper] rows pgid)))
            (deadline/bounded!
             budget "helper-release-closure-verification"
             #(closure/verify! reviewed-profile process-environment budget!))
            (gate/publish! budget helper-path token
                           "helper-execution-release")
            (let [finished (await-helper! state-path token futures
                                          execution-deadline ownership)]
              (deadline/check! budget "process-completion")
              (deadline/owned!
               budget "supervisor-retirement"
               #(retirement/release-process!
                 supervisor-process % execution-deadline remaining-nanos))
              (deadline/await-future! input execution-deadline "request-input")
              (let [stdout-bytes (deadline/await-future!
                                  stdout execution-deadline "stdout-drain")
                    stderr-bytes (deadline/await-future!
                                  stderr execution-deadline "stderr-drain")
                    closure-check
                    (.submit io-executor
                             ^Callable
                             #(closure/verify! reviewed-profile
                                               process-environment budget!))
                    process-result
                    {:source (:preflight reviewed-profile)
                     :reviewed-closure-sha256
                     (deadline/await-future!
                      closure-check execution-deadline "closure-recheck")
                     :exit-code (or (get finished "exitCode") 1)
                     :stdout (decode-utf8 stdout-bytes
                                          "Guidance preflight stdout")
                     :stderr (decode-utf8 stderr-bytes
                                          "Guidance preflight stderr")
                     :owned-pids {:supervisor-pid
                                  (:pid (:supervisor @ownership))
                                  :anchor-pid (:pid (:anchor @ownership))
                                  :helper-pid (:pid (:helper @ownership))}}]
                (deadline/check! budget "result-validation")
                (validate-result! process-result))))))
      (fn []
        (let [failure (atom nil)]
          (when @process
            (cleanup/attempt-operation! failure cleanup-process!))
          (doseq [stream @streams]
            (cleanup/attempt-operation! failure #(.close stream)))
          (when @executor
            (.shutdownNow ^java.util.concurrent.ExecutorService @executor))
          (when @process
            (cleanup/attempt-operation!
             failure
             #(retirement/finalize! @process operation-deadline
                                    remaining-nanos)))
          (when @directory
            (cleanup/attempt-operation!
             failure #(delete-directory! @directory)))
          (when-let [error @failure]
            (throw error))))))))

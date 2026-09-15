(ns ct.spools.harnesses.internal.guidance-capability
  "Admission and no-model preflight for native managed guidance."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline]
            [ct.spools.harnesses.internal.guidance-process :as guidance-process]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :refer [fail!]]))

(def preflight-schema
  "Version identifying the no-model preflight request and result."
  "millstrand.agent-guidance-preflight/v1")

(def capability-schema
  "Version identifying admitted provider capability evidence."
  "millstrand.agent-guidance-capability/v1")

(def adapter-contract
  "Native adapter contract selected by this Harnesses implementation."
  "native-v1")

(def ^:private metadata-limit (* 64 1024))
(def ^:private sha-pattern #"[0-9a-f]{64}")
(def ^:private required-request-keys
  #{"schema" "harness" "executable" "mode" "cwd" "workspace" "env"
    "extra-argv" "resumes"})
(def ^:private optional-request-keys
  #{"model" "effort" "native-session-id"})

;; This boundary is intentionally empty. Source acceptance in the Agents
;; repository and exact host conformance evidence are prerequisites for adding
;; a production profile.
(def ^:private production-capability-profiles [])

(def ^:dynamic *test-capability-profiles*
  "Test-only exact profiles replacing the empty production allowlist."
  nil)

(def ^:dynamic *test-preflight-runner*
  "Test-only process seam returning bounded preflight process evidence."
  nil)

(defn production-allowlist
  "Return the immutable production capability allowlist.

  It remains empty until independently accepted Agents artifacts and exact host
  evidence are available."
  []
  production-capability-profiles)

(defn file-sha256
  "Return the SHA-256 digest of one regular file."
  [path]
  (closure/file-sha256 path))

(defn resolve-executable
  "Resolve `command` against `env` and return its canonical executable path."
  [command env]
  (let [direct (io/file command)
        candidates
        (if (.isAbsolute direct)
          [direct]
          (for [directory (str/split (or (get env "PATH") "")
                                     (re-pattern
                                      (java.util.regex.Pattern/quote
                                       java.io.File/pathSeparator)))]
            (io/file directory command)))
        executable (some #(when (and (.isFile %) (.canExecute %)) %) candidates)]
    (or (some-> executable .getCanonicalPath)
        (fail! "Native guidance preflight cannot resolve the provider executable"
               {:command command}))))

(defn- profiles []
  (or *test-capability-profiles* production-capability-profiles))

(defn- closed-keys! [value required label]
  (when-not (= required (set (keys value)))
    (fail! (str label " has invalid keys")
           {:required (sort required) :actual (sort (keys value))})))

(defn- nonblank! [value label]
  (when-not (and (string? value) (not (str/blank? value)))
    (fail! (str label " must be a non-blank string") {:value value})))

(defn- sha! [value label]
  (when-not (and (string? value) (re-matches sha-pattern value))
    (fail! (str label " must be a lowercase SHA-256 digest") {:value value})))

(defn- validate-request! [request]
  (let [keys (set (keys request))
        executable (get request "executable")]
    (when-not (and (every? (into required-request-keys optional-request-keys)
                           keys)
                   (every? keys required-request-keys))
      (fail! "Guidance preflight request has invalid keys"
             {:required (sort required-request-keys)
              :allowed (sort (into required-request-keys
                                   optional-request-keys))
              :actual (sort keys)}))
    (when-not (= preflight-schema (get request "schema"))
      (fail! "Guidance preflight request has an unsupported schema" {}))
    (when-not (contains? #{"codex" "pi"} (get request "harness"))
      (fail! "Guidance preflight request names an unsupported provider" {}))
    (when-not (contains? #{"headless" "interactive"} (get request "mode"))
      (fail! "Guidance preflight request has an unsupported mode" {}))
    (doseq [key ["executable" "cwd" "workspace"]]
      (nonblank! (get request key) (str "Guidance preflight request " key)))
    (when-not (= executable (.getCanonicalPath (io/file executable)))
      (fail! "Guidance preflight executable must be canonical"
             {:executable executable}))
    (when-not (and (map? (get request "env"))
                   (every? (fn [[key value]]
                             (and (string? key) (string? value)))
                           (get request "env")))
      (fail! "Guidance preflight environment is malformed" {}))
    (when-not (and (vector? (get request "extra-argv"))
                   (every? string? (get request "extra-argv")))
      (fail! "Guidance preflight extra argv is malformed" {}))
    (when-not (boolean? (get request "resumes"))
      (fail! "Guidance preflight resume marker must be boolean" {}))
    (doseq [key ["model" "effort"]
            :when (contains? request key)]
      (nonblank! (get request key) (str "Guidance preflight request " key)))
    (if (get request "resumes")
      (nonblank! (get request "native-session-id")
                 "Guidance preflight request native-session-id")
      (when (contains? request "native-session-id")
        (fail! "Fresh guidance preflight cannot name a native session" {})))
    request))

(def ^:private capability-keys
  #{"schema" "harness" "adapter-contract" "adapter-sha256"
    "executable-sha256" "host-version" "launch-profile-sha256"
    "max-context-bytes" "hook-fact"})

(def ^:private codex-hook-keys
  #{"eventName" "key" "source" "sourcePath" "pluginId" "command"
    "enabled" "trustStatus" "currentHash" "timeoutSec"
    "additionalContextLimit"})

(def ^:private pi-hook-keys
  #{"host-package" "host-package-version" "host-package-sha256"
    "extensions" "prompt-owner-entrypoint" "system-prompt-options-contract"})

(def ^:private pi-extension-keys #{"entrypoint" "closure-sha256"})

(defn- validate-codex-hook! [hook]
  (closed-keys! hook codex-hook-keys "Codex guidance hook evidence")
  (doseq [key ["eventName" "key" "source" "sourcePath" "pluginId" "command"
               "trustStatus" "currentHash"]]
    (nonblank! (get hook key) (str "Codex hook " key)))
  (when-not (true? (get hook "enabled"))
    (fail! "Codex managed guidance hook is disabled" {}))
  (when-not (= "trusted" (get hook "trustStatus"))
    (fail! "Codex managed guidance hook is not trusted" {}))
  (when-not (and (integer? (get hook "timeoutSec"))
                 (<= 15 (get hook "timeoutSec")))
    (fail! "Codex managed guidance hook timeout is below 15 seconds" {}))
  (when-not (and (integer? (get hook "additionalContextLimit"))
                 (<= 4096 (get hook "additionalContextLimit")))
    (fail! "Codex managed guidance context limit is below 4096 bytes" {})))

(defn- validate-pi-hook! [hook]
  (closed-keys! hook pi-hook-keys "Pi guidance hook evidence")
  (doseq [key ["host-package" "host-package-version"
               "prompt-owner-entrypoint" "system-prompt-options-contract"]]
    (nonblank! (get hook key) (str "Pi hook " key)))
  (sha! (get hook "host-package-sha256") "Pi host package hash")
  (when-not (= "owned-v1" (get hook "system-prompt-options-contract"))
    (fail! "Pi prompt owner does not implement owned-v1" {}))
  (let [extensions (get hook "extensions")]
    (when-not (and (vector? extensions) (seq extensions))
      (fail! "Pi guidance evidence requires an extension closure" {}))
    (doseq [extension extensions]
      (closed-keys! extension pi-extension-keys "Pi extension evidence")
      (nonblank! (get extension "entrypoint") "Pi extension entrypoint")
      (sha! (get extension "closure-sha256") "Pi extension closure hash"))
    (when-not (= 1 (count (filter #(= (get hook "prompt-owner-entrypoint")
                                      (get % "entrypoint"))
                                  extensions)))
      (fail! "Pi guidance evidence does not name exactly one prompt owner" {}))))

(defn process-ownership-sha256
  "Hash the local executable closure, ownership policy, and wire capability."
  [profile]
  (closure/reviewed-sha256 profile))

(defn- validate-capability-shape! [capability harness]
  (closed-keys! capability capability-keys "Guidance capability")
  (doseq [key ["schema" "harness" "adapter-contract" "host-version"]]
    (nonblank! (get capability key) (str "Guidance capability " key)))
  (doseq [key ["adapter-sha256" "executable-sha256"
               "launch-profile-sha256"]]
    (sha! (get capability key) (str "Guidance capability " key)))
  (when-not (= capability-schema (get capability "schema"))
    (fail! "Guidance capability schema is unsupported" {}))
  (when-not (= harness (get capability "harness"))
    (fail! "Guidance capability names another provider" {}))
  (when-not (= adapter-contract (get capability "adapter-contract"))
    (fail! "Guidance adapter contract is unsupported" {}))
  (when-not (pos-int? (get capability "max-context-bytes"))
    (fail! "Guidance capability context limit must be a positive integer" {}))
  (case harness
    "codex" (validate-codex-hook! (get capability "hook-fact"))
    "pi" (validate-pi-hook! (get capability "hook-fact"))
    (fail! "Native guidance supports only Codex and Pi" {:harness harness}))
  capability)

(defn validate-document!
  "Return one normalized closed provider capability document or fail."
  [capability harness]
  (when-not (map? capability)
    (fail! "Guidance preflight capability must be an object" {}))
  (validate-capability-shape! (strict-json/canonical-data capability) harness))

(defn- validate-source! [profile source]
  (let [{expected-path :path expected-sha :sha256} (:preflight profile)]
    (when-not (and (string? expected-path) (not (str/blank? expected-path)))
      (fail! "Accepted guidance preflight source path is invalid" {}))
    (let [expected-file (io/file expected-path)]
      (when-not (and (= "managed-guidance-preflight.mjs"
                        (.getName expected-file))
                     (= "scripts" (.getName (.getParentFile expected-file))))
        (fail! "Accepted guidance preflight source has the wrong command path"
               {:path expected-path}))
      (when-not (= {:path (.getCanonicalPath expected-file)
                    :sha256 expected-sha}
                   source)
        (fail! "Guidance preflight source does not match the accepted artifact"
               {:expected (:preflight profile) :actual source})))))

(defn- validate-result! [profile harness executable process-result]
  (validate-source! profile (:source process-result))
  (when-not (= (get-in profile
                       [:process-ownership :reviewed-closure-sha256])
               (:reviewed-closure-sha256 process-result))
    (fail! "Guidance executable closure changed during preflight" {}))
  (when-not (zero? (:exit-code process-result))
    (fail! "Guidance preflight command failed"
           {:exit-code (:exit-code process-result)
            :diagnostic (:stderr process-result)}))
  (let [result (strict-json/parse-object! (:stdout process-result)
                                          metadata-limit
                                          "Guidance preflight response")]
    (when (= "legacy-required" (get result "result"))
      (closed-keys! result #{"schema" "result" "code" "diagnostic"}
                    "Guidance preflight failure")
      (fail! "Native guidance capability is unavailable; submit with --guidance-transport legacy"
             {:code (get result "code")
              :diagnostic (get result "diagnostic")}))
    (closed-keys! result #{"schema" "result" "capability"}
                  "Guidance preflight response")
    (when-not (and (= preflight-schema (get result "schema"))
                   (= "capable" (get result "result")))
      (fail! "Guidance preflight returned an unsupported result" {:result result}))
    (let [capability (validate-document! (get result "capability") harness)]
      (when-not (= (:capability profile) capability)
        (fail! "Guidance capability does not match the accepted host profile" {}))
      (when-not (= (file-sha256 executable)
                   (get capability "executable-sha256"))
        (fail! "Provider executable does not match guidance capability evidence"
               {:executable executable}))
      capability)))

(defn- verify-profile! [budget profile environment phase]
  (deadline/bounded!
   budget phase
   #(closure/verify!
     profile environment
     (fn [] (deadline/check! budget phase)))))

(defn- validate-operation-result!
  [budget profile harness executable environment process-result]
  (verify-profile! budget profile environment
                   "post-execution-closure-verification")
  (deadline/bounded!
   budget "result-validation"
   #(validate-result! profile harness executable process-result)))

(defn preflight!
  "Run and admit one exact native capability before managed publication.

  One monotonic budget begins at entry and covers all validation, hashing,
  closure checks, process work, result validation, and cleanup."
  [request]
  (let [budget (deadline/start)
        runner *test-preflight-runner*
        harness (get request "harness")
        executable (get request "executable")
        _ (deadline/check! budget "profile-selection")
        matching (filterv #(= harness (:harness %)) (profiles))]
    (deadline/check! budget "profile-selection")
    (when (empty? matching)
      (fail! "Native guidance is disabled: no accepted production capability exists; submit with --guidance-transport legacy"
             {:harness harness :production-allowlist-empty
              (empty? production-capability-profiles)}))
    (when-not (= 1 (count matching))
      (fail! "Native guidance has duplicate accepted preflight sources"
             {:harness harness :profiles (count matching)}))
    (let [profile (first matching)
          wire-request
          (deadline/bounded!
           budget "request-validation"
           #(validate-request! (assoc request "schema" preflight-schema)))
          _ (deadline/bounded!
             budget "profile-validation"
             #(validate-document! (:capability profile) harness))
          _ (deadline/check! budget "executable-hashing")
          executable-sha
          (deadline/bounded! budget "executable-hashing"
                             #(file-sha256 executable))
          _ (when-not (= executable-sha
                         (get-in profile [:capability "executable-sha256"]))
              (fail! "Provider executable does not match guidance capability evidence"
                     {:executable executable}))
          environment (get wire-request "env")
          _ (verify-profile! budget profile environment
                             "admission-closure-verification")
          request-json
          (deadline/bounded!
           budget "request-serialization"
           #(let [json (strict-json/canonical-json wire-request)]
              (when (> (strict-json/utf8-bytes json) metadata-limit)
                (fail! "Guidance preflight request exceeds 64 KiB" {}))
              json))
          operation-profile (assoc profile :effective-environment environment)
          validate-result
          #(validate-operation-result! budget profile harness executable
                                       environment %)
          capability
          (if runner
            (let [process-result
                  (deadline/bounded!
                   budget "process-execution"
                   #(runner operation-profile request-json))]
              (validate-result process-result))
            (guidance-process/run! operation-profile request-json
                                   budget validate-result))]
      (deadline/check! budget "capability-admission")
      capability)))

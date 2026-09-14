(ns ct.spools.harnesses.internal.guidance
  "Frozen managed-guidance selection, handoff, and receipt lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-prompt-controls :as prompt-controls]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :as spool])
  (:import [java.time Instant]))

(def guidance-version
  "Durable managed-guidance representation version."
  1)

(def guidance-bootstrap-schema
  "Version identifying launcher guidance-routing metadata."
  "millstrand.agent-guidance-bootstrap/v1")

(def guidance-bundle-schema
  "Version identifying a frozen managed-guidance bundle."
  "millstrand.agent-guidance-bundle/v1")

(def guidance-context-schema
  "Version identifying the structured managed context in a bundle."
  "millstrand.agent-managed-context/v1")

(def transports
  "Public managed-guidance transport names."
  #{"legacy" "native-v1"})

(def ^:private managed-harnesses #{"codex" "pi"})
(def ^:private guidance-attribute-keys
  #{:harness/guidance-version :harness/guidance-transport
    :harness/guidance-capability :harness/guidance-capability-sha256
    :harness/guidance-context-template :harness/guidance-context
    :harness/guidance-bundle-sha256 :harness/guidance-attempts})
(def ^:private bootstrap-keys
  #{"schema" "transport" "run-id" "attempt" "invocation" "harness"
    "bundle-sha256" "capability-sha256"})

(def ^:private metadata-limit (* 64 1024))
(def ^:private bundle-limit (* 1024 1024))
(def ^:private provider-context-limits {"codex" 3072 "pi" 65536})
(def ^:private acknowledgement-deadline-seconds 20)

(defn parse-transport
  "Parse an explicit transport name, failing on unsupported values."
  [value]
  (let [transport (cond
                    (keyword? value) (name value)
                    (string? value) value
                    :else nil)]
    (if (contains? transports transport)
      transport
      (spool/fail! "--guidance-transport must be legacy or native-v1"
                   {:guidance-transport value}))))

(defn- canonical-path [path label]
  (when-not (and (string? path) (not (str/blank? path)))
    (spool/fail! (str "Native guidance requires " label) {label path}))
  (.getCanonicalPath (io/file path)))

(defn- workspace [rt]
  (canonical-path
   (or (get-in rt [:metadata :config-dir])
       (spool/fail! "Native guidance requires a selected workspace" {}))
   "workspace"))

(defn- preflight-request [rt {:keys [harness mode cwd env effective session-id
                                     resumes]}]
  (let [overlay-env (into {} (map (fn [[key value]] [(name key) value]))
                          (or env {}))
        actual-env (merge (into {} (System/getenv)) overlay-env)
        executable (capability/resolve-executable harness actual-env)]
    (cond-> {"harness" harness
             "executable" executable
             "mode" (name mode)
             "cwd" (canonical-path cwd "cwd")
             "workspace" (workspace rt)
             "env" actual-env
             "extra-argv" (or (:harness/extra-argv effective) [])
             "resumes" (boolean resumes)}
      (:harness/model effective)
      (assoc "model" (:harness/model effective))
      (:harness/effort effective)
      (assoc "effort" (:harness/effort effective))
      resumes (assoc "native-session-id" session-id))))

(defn select!
  "Select and preflight guidance before run publication or identity reservation."
  [rt {:keys [harness mode requested inherited effective] :as request}]
  (let [transport (parse-transport (or requested inherited "legacy"))]
    (when (and (= "native-v1" transport)
               (= :interactive mode)
               (contains? managed-harnesses harness))
      (spool/fail!
       "Native guidance does not support interactive launches; submit legacy work"
       {:harness harness :mode mode :guidance-transport transport}))
    (if-not (contains? managed-harnesses harness)
      (do
        (when (= "native-v1" transport)
          (spool/fail! "Native guidance supports only Codex and Pi"
                       {:harness harness}))
        nil)
      (if (= "legacy" transport)
        {:transport transport}
        (do
          (prompt-controls/reject! harness
                                   (or (:harness/extra-argv effective) []))
          (let [capability (capability/preflight!
                            (preflight-request rt request))]
            (when-not (= (get provider-context-limits harness)
                         (get capability "max-context-bytes"))
              (spool/fail! "Guidance capability has an unaccepted context limit"
                           {:harness harness
                            :expected (get provider-context-limits harness)
                            :actual (get capability "max-context-bytes")}))
            {:transport transport
             :capability capability
             :capability-sha256
             (strict-json/canonical-sha256 capability)}))))))

(defn- normalize-context [context]
  (when (map? context)
    (reduce-kv
     (fn [result key value]
       (let [key (name key)]
         (when (contains? result key)
           (spool/fail! "Frozen guidance context has colliding keys" {:key key}))
         (assoc result key value)))
     {}
     context)))

(defn- valid-context! [context]
  (let [context (normalize-context context)]
    (when-not (= #{"schema" "identity-instruction" "appended-system-prompts"}
                 (set (keys context)))
      (spool/fail! "Frozen guidance context has invalid keys" {}))
    (when-not (= guidance-context-schema (get context "schema"))
      (spool/fail! "Frozen guidance context has an unsupported schema" {}))
    (when-not (and (string? (get context "identity-instruction"))
                   (not (str/blank? (get context "identity-instruction"))))
      (spool/fail! "Frozen guidance identity instruction is invalid" {}))
    (let [appends (get context "appended-system-prompts")]
      (when-not (and (vector? appends)
                     (every? #(and (string? %) (not (str/blank? %))) appends))
        (spool/fail! "Frozen guidance appends must be a vector of non-blank strings"
                     {:appended-system-prompts appends})))
    context))

(defn- bind-markers [value run-id identity-id]
  (cond
    (string? value) (-> value
                        (str/replace "{{RUN_ID}}" run-id)
                        (str/replace "{{AGENT_ID}}" identity-id))
    (map? value) (into {} (map (fn [[key item]]
                                 [key (bind-markers item run-id identity-id)]))
                       value)
    (vector? value) (mapv #(bind-markers % run-id identity-id) value)
    :else value))

(defn- footer [run-id workspace]
  (str "Current Millstrand run: " run-id
       ". Pass --workspace " (strict-json/canonical-json workspace)
       " on Strand commands. This is the current managed guidance; earlier "
       "run guidance is historical."))

(defn- rendered-context [run-id workspace context]
  (str/join "\n\n"
            (concat [(get context "identity-instruction")]
                    (get context "appended-system-prompts")
                    [(footer run-id workspace)])))

(defn publication-patch
  "Freeze and digest one selected guidance bundle before final publication."
  [rt run-id identity-id identity-instruction appends selection frozen-template
   prior-attempts]
  (when selection
    (let [template (valid-context!
                    (or frozen-template
                        {"schema" guidance-context-schema
                         "identity-instruction" identity-instruction
                         "appended-system-prompts" (vec (or appends []))}))
          _ (when-not (= identity-instruction
                         (get template "identity-instruction"))
              (spool/fail! "Frozen guidance identity does not match the reserved identity"
                           {:run-id run-id}))
          context (valid-context! (bind-markers template run-id identity-id))
          workspace (workspace rt)
          digest (strict-json/canonical-sha256 [run-id workspace context])
          rendered (rendered-context run-id workspace context)
          transport (:transport selection)]
      (when (= "native-v1" transport)
        (let [maximum (get-in selection [:capability "max-context-bytes"])
              actual (strict-json/utf8-bytes rendered)]
          (when (> actual maximum)
            (spool/fail! "Frozen managed guidance exceeds the accepted host limit"
                         {:run-id run-id :actual-bytes actual :max-bytes maximum
                          :remedy "Reduce appended guidance or submit legacy work."}))))
      (cond-> {:harness/guidance-version guidance-version
               :harness/guidance-transport transport
               :harness/guidance-context-template template
               :harness/guidance-context context
               :harness/guidance-bundle-sha256 digest
               :harness/guidance-attempts (vec (or prior-attempts []))}
        (= "legacy" transport)
        (assoc :harness/guidance-capability nil
               :harness/guidance-capability-sha256 nil)
        (= "native-v1" transport)
        (assoc :harness/guidance-capability (:capability selection)
               :harness/guidance-capability-sha256
               (:capability-sha256 selection))))))

(defn transport
  "Return and validate a run's selected transport; absent old metadata is legacy."
  [run]
  (let [present (filter #(some? (spool/attr-get run %))
                        guidance-attribute-keys)]
    (if (empty? present)
      "legacy"
      (let [version (spool/attr-get run :harness/guidance-version)
            selected (spool/attr-get run :harness/guidance-transport)
            required (cond-> (disj guidance-attribute-keys
                                   :harness/guidance-capability
                                   :harness/guidance-capability-sha256)
                       (= selected "native-v1")
                       (conj :harness/guidance-capability
                             :harness/guidance-capability-sha256))
            missing (remove #(some? (spool/attr-get run %)) required)]
        (when-not (and (= guidance-version version)
                       (contains? transports selected)
                       (empty? missing))
          (spool/fail! "Harness run has corrupt partial guidance metadata"
                       {:run-id (:id run) :version version
                        :transport selected :missing (vec missing)}))
        selected))))

(defn native?
  "Return whether `run` has a complete native-v1 selection."
  [run]
  (= "native-v1" (transport run)))

(defn attempt-records
  "Return guidance attempt records with normalized string keys."
  [run]
  (mapv (fn [record]
          (into {}
                (map (fn [[key value]]
                       [(name key)
                        (if (and (= "failure" (name key)) (map? value))
                          (into {} (map (fn [[failure-key failure-value]]
                                          [(name failure-key) failure-value]))
                                value)
                          value)]))
                record))
        (or (spool/attr-get run :harness/guidance-attempts) [])))

(defn current-attempt
  "Return the guidance record for the run's current durable launch fence."
  [run]
  (let [attempt (spool/attr-get run :harness/attempt)
        invocation (spool/attr-get run :harness/invocation)]
    (some #(when (and (= attempt (get % "attempt"))
                      (= invocation (get % "invocation")))
             %)
          (attempt-records run))))

(defn preflight-failure-patch
  "Return a terminal no-launch patch for execution-time native preflight failure."
  [run attempt invocation error]
  (let [diagnostic
        (str "Native guidance preflight failed for run " (:id run)
             " attempt " attempt ": " (ex-message error)
             ". Repair or approve the reviewed adapter/configuration, or "
             "explicitly submit legacy work after settlement.")]
    {:harness/guidance-attempts
     (conj (attempt-records run)
           {"attempt" attempt
            "invocation" invocation
            "transport" "native-v1"
            "bundle-sha256"
            (spool/attr-get run :harness/guidance-bundle-sha256)
            "capability-sha256"
            (spool/attr-get run :harness/guidance-capability-sha256)
            "state" "failed"
            "started-at" (life/now)
            "failure" {"stage" "preflight"
                       "code" (or (:code (ex-data error))
                                  "capability-mismatch")
                       "diagnostic" diagnostic}})
     :harness/attempt attempt
     :harness/invocation invocation
     :harness/started-at (life/now)
     :harness/status "failed"
     :harness/substatus "bootstrap"
     :harness/settled "true"
     :harness/settlement "launch-not-started"
     :harness/session-usable "false"
     :harness/error diagnostic}))

(defn begin-attempt-patch
  "Return a fenced guidance-attempt patch, rechecking native capability first."
  [rt run attempt invocation]
  (if-not (some? (spool/attr-get run :harness/guidance-version))
    {}
    (let [selected (transport run)
          attempts (attempt-records run)
          now (Instant/now)]
      (when (= "native-v1" selected)
        (let [selection (select!
                         rt {:harness (spool/attr-get run :harness/harness)
                             :requested selected
                             :mode (keyword (spool/attr-get run :harness/mode))
                             :cwd (spool/attr-get run :harness/cwd)
                             :env (spool/attr-get run :harness/env)
                             :effective (:attributes run)
                             :session-id (spool/attr-get run :harness/session-id)
                             :resumes (spool/attr-get run :harness/resumes)})
              stored-capability
              (spool/attr-get run :harness/guidance-capability)
              stored-digest
              (spool/attr-get run :harness/guidance-capability-sha256)]
          (when-not (= (:capability-sha256 selection)
                       stored-digest
                       (strict-json/canonical-sha256 stored-capability))
            (spool/fail! "Native guidance capability changed before execution"
                         {:run-id (:id run)}))))
      {:harness/guidance-attempts
       (conj attempts
             (cond-> {"attempt" attempt
                      "invocation" invocation
                      "transport" selected
                      "state" (if (= "native-v1" selected)
                                "pending"
                                "not-required")
                      "started-at" (str now)}
               (= "native-v1" selected)
               (assoc "bundle-sha256"
                      (spool/attr-get run :harness/guidance-bundle-sha256)
                      "capability-sha256"
                      (spool/attr-get run :harness/guidance-capability-sha256)
                      "deadline-at"
                      (str (.plusSeconds now acknowledgement-deadline-seconds)))))})))

(defn deadline-expired?
  "Return whether a native handoff record has crossed its durable deadline."
  ([run record]
   (deadline-expired? run record (Instant/now)))
  ([run record now]
   (and (= "native-v1" (transport run))
        (contains? #{"pending" "fetched"} (get record "state"))
        (not (and (= "pi" (spool/attr-get run :harness/harness))
                  (= "interactive" (spool/attr-get run :harness/mode))
                  (= "fetched" (get record "state"))))
        (let [deadline (get record "deadline-at")]
          (when-not (and (string? deadline) (not (str/blank? deadline)))
            (spool/fail! "Native guidance attempt has no deadline"
                         {:run-id (:id run)}))
          (not (.isBefore now (Instant/parse deadline)))))))

(defn bootstrap
  "Return the launcher guidance document for the current attempt."
  [run]
  (let [selected (transport run)]
    (if (= "legacy" selected)
      {"schema" guidance-bootstrap-schema "transport" "legacy"}
      (let [record (current-attempt run)]
        (when-not (and record (contains? #{"pending" "fetched" "acknowledged"}
                                         (get record "state")))
          (spool/fail! "Native guidance has no active handoff attempt"
                       {:run-id (:id run)}))
        {"schema" guidance-bootstrap-schema
         "transport" "native-v1"
         "run-id" (:id run)
         "attempt" (get record "attempt")
         "invocation" (get record "invocation")
         "harness" (spool/attr-get run :harness/harness)
         "bundle-sha256" (get record "bundle-sha256")
         "capability-sha256" (get record "capability-sha256")}))))

(defn- normalize-document [value label]
  (cond
    (string? value) (strict-json/parse-object! value metadata-limit label)
    (map? value) (into {} (map (fn [[key item]] [(name key) item])) value)
    :else (spool/fail! (str label " must be a JSON object") {:value value})))

(defn validate-startup
  "Validate optional startup guidance and return its normalized document."
  [run value]
  (let [selected (transport run)]
    (if (= "legacy" selected)
      (when value
        (let [document (normalize-document value "Guidance bootstrap")]
          (when-not (= {"schema" guidance-bootstrap-schema
                        "transport" "legacy"}
                       document)
            (spool/fail! "Legacy guidance bootstrap is malformed" {}))
          document))
      (do
        (when-not value
          (spool/fail! "Native startup requires --guidance" {:run-id (:id run)}))
        (let [record (current-attempt run)
              _ (when (deadline-expired? run record)
                  (spool/fail! "Native guidance startup crossed its handoff deadline"
                               {:run-id (:id run)
                                :attempt (get record "attempt")}))
              document (normalize-document value "Guidance bootstrap")
              expected (bootstrap run)]
          (when-not (= bootstrap-keys (set (keys document)))
            (spool/fail! "Native guidance bootstrap has invalid keys"
                         {:required (sort bootstrap-keys)
                          :actual (sort (keys document))}))
          (when-not (= expected document)
            (spool/fail! "Native guidance bootstrap does not match the run"
                         {:run-id (:id run)}))
          document)))))

(defn fetch-patch
  "Return the exact-current native attempt transition to fetched."
  [run]
  (when (native? run)
    (let [record (current-attempt run)
          state (get record "state")]
      (when-not (contains? #{"pending" "fetched" "acknowledged"} state)
        (spool/fail! "Native guidance cannot be fetched in its current state"
                     {:run-id (:id run) :state state}))
      (when (= "pending" state)
        {:harness/guidance-attempts
         (mapv #(if (= record %) (assoc % "state" "fetched") %)
               (attempt-records run))}))))

(defn bundle
  "Return the frozen current-run bundle after native attachment."
  [rt run native-session-id identity strand-id]
  (let [value {:schema guidance-bundle-schema
               :run-id (:id run)
               :attempt (spool/attr-get run :harness/attempt)
               :invocation (spool/attr-get run :harness/invocation)
               :harness (spool/attr-get run :harness/harness)
               :native-session-id native-session-id
               :identity identity
               :strand-id strand-id
               :workspace (workspace rt)
               :transport "native-v1"
               :bundle-sha256
               (spool/attr-get run :harness/guidance-bundle-sha256)
               :capability-sha256
               (spool/attr-get run :harness/guidance-capability-sha256)
               :context (spool/attr-get run :harness/guidance-context)}]
    (when (> (strict-json/utf8-bytes (strict-json/canonical-json value))
             bundle-limit)
      (spool/fail! "Frozen guidance bundle exceeds 1 MiB" {:run-id (:id run)}))
    value))

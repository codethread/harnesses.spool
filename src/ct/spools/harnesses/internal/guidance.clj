(ns ct.spools.harnesses.internal.guidance
  "Frozen managed-guidance selection, handoff, and receipt lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-context :as guidance-context]
            [ct.spools.harnesses.internal.guidance-history :as history]
            [ct.spools.harnesses.internal.guidance-prompt-controls :as prompt-controls]
            [ct.spools.harnesses.internal.guidance-representation :as representation]
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
  guidance-context/schema)

(def transports
  "Public managed-guidance transport names."
  #{"legacy" "native-v1" "launch"})

(def ^:private native-identity-harnesses
  "Providers whose identity and run registration come from native startup."
  #{"codex" "pi"})
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

(defn validation-run
  "Attach the runtime's canonical workspace to one process-local run value."
  [rt run]
  (if (get-in rt [:metadata :config-dir])
    (representation/attach-context run (workspace rt))
    run))

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
  (when (and (= "pi" harness) requested)
    (spool/fail!
     "Pi uses native identity and ordinary prompts; guidance transport is not selectable"
     {}))
  (if (contains? native-identity-harnesses harness)
    (do
      (when (and requested (not= "launch" requested))
        (spool/fail!
         "Native identity providers use ordinary launch prompts; transport selection is unsupported"
         {:harness harness}))
      nil)
    (let [transport (parse-transport (or requested inherited "legacy"))]
      (when (and (= "native-v1" transport)
                 (= :interactive mode)
                 (contains? native-identity-harnesses harness))
        (spool/fail!
         "Native guidance does not support interactive launches; submit legacy work"
         {:harness harness :mode mode :guidance-transport transport}))
      (if-not (contains? native-identity-harnesses harness)
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
            (let [preflight (preflight-request rt request)
                  capability (capability/preflight! preflight)]
              (when-not (= (get provider-context-limits harness)
                           (get capability "max-context-bytes"))
                (spool/fail! "Guidance capability has an unaccepted context limit"
                             {:harness harness
                              :expected (get provider-context-limits harness)
                              :actual (get capability "max-context-bytes")}))
              {:transport transport
               :capability capability
               :capability-sha256
               (strict-json/canonical-sha256 capability)
               :launch-plan
               {:harness harness
                :executable (get preflight "executable")
                :cwd (get preflight "cwd")
                :env (get preflight "env")
                :selectors
                (select-keys preflight
                             ["extra-argv" "model" "effort" "resumes"
                              "native-session-id"])}})))))))

(defn publication-patch
  "Freeze and digest one selected guidance bundle before final publication."
  [rt run-id identity-id identity-instruction appends selection frozen-template
   prior-attempts]
  (when selection
    (let [template (guidance-context/validate!
                    (or frozen-template
                        {"schema" guidance-context-schema
                         "identity-instruction" identity-instruction
                         "appended-system-prompts" (vec (or appends []))}))
          _ (when-not (= identity-instruction
                         (get template "identity-instruction"))
              (spool/fail! "Frozen guidance identity does not match the reserved identity"
                           {:run-id run-id}))
          context (guidance-context/validate!
                   (guidance-context/bind-markers
                    template run-id identity-id))
          workspace (workspace rt)
          digest (guidance-context/bundle-sha256 run-id workspace context)
          rendered (guidance-context/rendered run-id workspace context)
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

(defn validate-representation!
  "Return the complete durable guidance representation for one run."
  [run]
  (representation/validate! run))

(defn transport
  "Return and validate a run's selected transport; absent old metadata is legacy."
  [run]
  (if (= "codex" (spool/attr-get run :harness/harness))
    "launch"
    (:transport (validate-representation! run))))

(defn native?
  "Return whether `run` has a complete native-v1 selection."
  [run]
  (= "native-v1" (transport run)))

(defn attempt-records
  "Return guidance attempt records with normalized string keys."
  [run]
  (:attempts (validate-representation! run)))

(defn current-attempt
  "Return the guidance record for the run's current durable launch fence."
  [run]
  (let [attempt (spool/attr-get run :harness/attempt)
        invocation (spool/attr-get run :harness/invocation)]
    (some #(when (and (= attempt (get % "attempt"))
                      (= invocation (get % "invocation")))
             %)
          (attempt-records run))))

(defn retire-current-attempt
  "Retain current native evidence before a retry replaces outer fields."
  [run]
  (let [{:keys [attempts]} (validate-representation! run)
        current (current-attempt run)]
    (if (and current (= "native-v1" (get current "transport")))
      (let [retired (history/retire run current)]
        (history/validate-retired! run retired)
        (mapv #(if (= current %) retired %) attempts))
      attempts)))

(defn preflight-failure-patch
  "Return a terminal no-launch patch for execution-time native preflight failure."
  [run attempt invocation error]
  (let [now (life/now)
        diagnostic
        (str "Native guidance preflight failed for run " (:id run)
             " attempt " attempt ": " (ex-message error)
             ". Repair or approve the reviewed adapter/configuration, or "
             "explicitly submit legacy work after settlement.")]
    {:harness/guidance-attempts
     (conj (attempt-records run)
           {"attempt" attempt
            "invocation" invocation
            "transport" "native-v1"
            "harness" (spool/attr-get run :harness/harness)
            "mode" (spool/attr-get run :harness/mode)
            "bundle-sha256"
            (spool/attr-get run :harness/guidance-bundle-sha256)
            "capability-sha256"
            (spool/attr-get run :harness/guidance-capability-sha256)
            "state" "failed"
            "started-at" now
            "no-launch" {"attempt" attempt
                         "invocation" invocation
                         "authority" "harness-admission/v1"}
            "failure" {"stage" "preflight"
                       "code" (or (:code (ex-data error))
                                  "capability-mismatch")
                       "diagnostic" diagnostic}})
     :harness/attempt attempt
     :harness/invocation invocation
     :harness/started-at now
     :harness/status "failed"
     :harness/substatus "bootstrap"
     :harness/settled "true"
     :harness/settlement "launch-not-started"
     :harness/session-usable "false"
     :harness/error diagnostic}))

(defn- execution-selection! [rt run selected]
  (let [selection (select!
                   rt {:harness (spool/attr-get run :harness/harness)
                       :requested selected
                       :mode (keyword (spool/attr-get run :harness/mode))
                       :cwd (spool/attr-get run :harness/cwd)
                       :env (spool/attr-get run :harness/env)
                       :effective (:attributes run)
                       :session-id (spool/attr-get run :harness/session-id)
                       :resumes (spool/attr-get run :harness/resumes)})
        stored-capability (spool/attr-get run :harness/guidance-capability)
        stored-digest
        (spool/attr-get run :harness/guidance-capability-sha256)]
    (when-not (= (:capability-sha256 selection)
                 stored-digest
                 (strict-json/canonical-sha256 stored-capability))
      (spool/fail! "Native guidance capability changed before execution"
                   {:run-id (:id run)}))
    selection))

(defn begin-attempt-patch
  "Return a fenced guidance-attempt patch, rechecking native capability first."
  [rt run attempt invocation]
  (let [{:keys [versioned? transport attempts]}
        (validate-representation! run)]
    (if-not versioned?
      {}
      (let [selected transport
            selection (when (= "native-v1" selected)
                        (execution-selection! rt run selected))
            now (Instant/now)
            patch
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
                     (assoc "harness" (spool/attr-get run :harness/harness)
                            "mode" (spool/attr-get run :harness/mode)
                            "bundle-sha256"
                            (spool/attr-get run :harness/guidance-bundle-sha256)
                            "capability-sha256"
                            (spool/attr-get run
                                            :harness/guidance-capability-sha256)
                            "deadline-at"
                            (str (.plusSeconds
                                  now acknowledgement-deadline-seconds)))))}]
        (if selection
          (with-meta patch {::launch-plan (:launch-plan selection)})
          patch)))))

(defn carry-launch-plan
  "Carry a private attempt launch plan in process-local result metadata."
  [attempt-patch result]
  (if-let [plan (::launch-plan (meta attempt-patch))]
    (with-meta result {::launch-plan plan})
    result))

(defn launch-plan
  "Return a process-local validated launch plan without durable disclosure."
  [attempt-result]
  (::launch-plan (meta attempt-result)))

(defn deadline-expired?
  "Return whether a native handoff record has crossed its durable deadline."
  ([run record]
   (deadline-expired? run record (Instant/now)))
  ([run record now]
   (and (= "native-v1" (transport run))
        (contains? #{"pending" "fetched"} (get record "state"))
        (not (and (= "pi" (get record "harness"))
                  (= "interactive" (get record "mode"))
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
  "Return a timely exact-current transition with local first-fetch evidence."
  [run native-session-id fetched-at]
  (when (native? run)
    (let [record (current-attempt run)
          state (get record "state")]
      (when-not (contains? #{"pending" "fetched" "acknowledged"} state)
        (spool/fail! "Native guidance cannot be fetched in its current state"
                     {:run-id (:id run) :state state}))
      (when (= "pending" state)
        (when (deadline-expired? run record (Instant/parse fetched-at))
          (spool/fail! "Native guidance first fetch crossed its handoff deadline"
                       {:run-id (:id run) :attempt (get record "attempt")}))
        {:harness/guidance-attempts
         (mapv #(if (= record %)
                  (assoc %
                         "state" "fetched"
                         "first-fetch"
                         {"attempt" (get record "attempt")
                          "invocation" (get record "invocation")
                          "fetched-at" fetched-at
                          "native-session-id" native-session-id
                          "authority" "harness-managed-startup/v1"})
                  %)
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

(ns ct.spools.harnesses.internal.guidance
  "Frozen managed-guidance selection, handoff, and receipt lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-context :as guidance-context]
            [ct.spools.harnesses.internal.guidance-history :as history]
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

(defn select!
  "Validate explicit transport selection; all providers use launch prompts.

  Native identity providers reject the removed guidance transports. Maintenance
  providers retain their ordinary legacy prompt path. No capability is admitted."
  [_rt {:keys [harness requested inherited]}]
  (if (contains? native-identity-harnesses harness)
    (when (some? requested)
      (spool/fail!
       "Native identity providers use ordinary launch prompts; transport selection is unsupported"
       {:harness harness}))
    (let [transport (parse-transport (or requested inherited "legacy"))]
      (when (= "native-v1" transport)
        (spool/fail! "Native guidance is no longer supported"
                     {:harness harness}))))
  nil)

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

(defn begin-attempt-patch
  "Return a fenced legacy attempt patch; reject the removed native transport."
  [_rt run attempt invocation]
  (let [{:keys [versioned? transport attempts]}
        (validate-representation! run)]
    (when (= "native-v1" transport)
      (spool/fail! "Native guidance is no longer supported" {:run-id (:id run)}))
    (if-not versioned?
      {}
      {:harness/guidance-attempts
       (conj attempts
             {"attempt" attempt
              "invocation" invocation
              "transport" transport
              "state" "not-required"
              "started-at" (str (Instant/now))})})))

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

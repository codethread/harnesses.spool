(ns ct.spools.harnesses.internal.guidance-representation
  "Closed durable discriminator for managed-guidance run metadata."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-context :as context]
            [ct.spools.harnesses.internal.guidance-history :as history]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :as spool])
  (:import [java.time Instant]
           [java.time.format DateTimeParseException]))

(def attribute-keys
  "Complete durable guidance representation surface."
  [:harness/guidance-version
   :harness/guidance-transport
   :harness/guidance-capability
   :harness/guidance-capability-sha256
   :harness/guidance-context-template
   :harness/guidance-context
   :harness/guidance-bundle-sha256
   :harness/guidance-attempts])

(def ^:private sha-pattern #"[0-9a-f]{64}")
(def ^:private provider-context-limits {"codex" 3072 "pi" 65536})
(def ^:private failure-stages
  #{"preflight" "startup" "validation" "rendering" "handoff"})
(def ^:private base-attempt-keys
  #{"attempt" "invocation" "transport" "state" "started-at"})
(def ^:private native-attempt-keys
  #{"bundle-sha256" "capability-sha256" "harness" "mode"})
(def ^:private deadline-key #{"deadline-at"})
(def ^:private acknowledgement-key #{"acknowledged-at"})
(def ^:private failure-key #{"failure"})
(def ^:private first-fetch-key #{"first-fetch"})
(def ^:private no-launch-key #{"no-launch"})
(def ^:private failure-keys #{"stage" "code" "diagnostic"})
(def ^:private first-fetch-keys
  #{"attempt" "invocation" "fetched-at" "native-session-id" "authority"})
(def ^:private no-launch-keys #{"attempt" "invocation" "authority"})
(def ^:private handoff-seconds 20)
(def ^:private validation-context-key ::validation-context)

(defn attach-context
  "Attach the authoritative canonical workspace to a process-local run value."
  [run canonical-workspace]
  (vary-meta run assoc validation-context-key
             {:canonical-workspace canonical-workspace}))

(defn- attribute [run key]
  (get (:attributes run) key))

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn- sha? [value]
  (and (string? value) (re-matches sha-pattern value)))

(defn- parse-instant! [value label]
  (when-not (nonblank? value)
    (spool/fail! (str label " must be a non-blank timestamp") {}))
  (try
    (Instant/parse value)
    (catch DateTimeParseException _
      (spool/fail! (str label " must be an RFC 3339 instant")
                   {:value value}))))

(defn- closed? [record expected-keys]
  (= expected-keys (set (keys record))))

(defn- valid-failure! [failure]
  (when-not (and (map? failure)
                 (closed? failure failure-keys)
                 (contains? failure-stages (get failure "stage"))
                 (nonblank? (get failure "code"))
                 (nonblank? (get failure "diagnostic")))
    (spool/fail! "Guidance attempt failure is malformed" {})))

(defn- valid-no-launch! [record]
  (let [provenance (get record "no-launch")]
    (when-not (and (map? provenance)
                   (closed? provenance no-launch-keys)
                   (= (get record "attempt") (get provenance "attempt"))
                   (= (get record "invocation")
                      (get provenance "invocation"))
                   (= "harness-admission/v1" (get provenance "authority")))
      (spool/fail! "Guidance no-launch provenance is malformed" {}))))

(defn- valid-first-fetch! [record started-at deadline]
  (let [provenance (get record "first-fetch")
        fetched-at (when (map? provenance)
                     (parse-instant! (get provenance "fetched-at")
                                     "Guidance attempt first fetch"))]
    (when-not (and (map? provenance)
                   (closed? provenance first-fetch-keys)
                   (= (get record "attempt") (get provenance "attempt"))
                   (= (get record "invocation")
                      (get provenance "invocation"))
                   (nonblank? (get provenance "native-session-id"))
                   (= "harness-managed-startup/v1"
                      (get provenance "authority"))
                   (not (.isBefore fetched-at started-at))
                   (.isBefore fetched-at deadline))
      (spool/fail! "Guidance first-fetch provenance is malformed" {}))
    fetched-at))

(defn- delayed-pi-acknowledgement? [record]
  (and (= "pi" (get record "harness"))
       (= "interactive" (get record "mode"))
       (contains? record "first-fetch")
       (contains? #{"acknowledged" "failed"} (get record "state"))))

(defn- validate-time-order! [record started-at]
  (when (contains? record "deadline-at")
    (let [deadline (parse-instant! (get record "deadline-at")
                                   "Guidance attempt deadline")
          first-fetch-at (when (contains? record "first-fetch")
                           (valid-first-fetch! record started-at deadline))]
      (when-not (= deadline (.plusSeconds started-at handoff-seconds))
        (spool/fail! "Guidance attempt deadline is not its 20 second fence" {}))
      (when (contains? record "acknowledged-at")
        (let [acknowledged-at
              (parse-instant! (get record "acknowledged-at")
                              "Guidance attempt acknowledgement")]
          (when (or (.isBefore acknowledged-at started-at)
                    (and first-fetch-at
                         (.isBefore acknowledged-at first-fetch-at))
                    (and (.isAfter acknowledged-at deadline)
                         (not (delayed-pi-acknowledgement? record))))
            (spool/fail!
             "Guidance attempt acknowledgement is outside its handoff window"
             {})))))))

(defn- allowed-native-keys [state]
  (let [base (into base-attempt-keys native-attempt-keys)]
    (case state
      "pending" [(into base deadline-key)]
      "fetched" [(into base (into deadline-key first-fetch-key))]
      "acknowledged" [(into base (into deadline-key
                                       (into first-fetch-key
                                             acknowledgement-key)))]
      "failed" [(into base (into no-launch-key failure-key))
                (into base (into deadline-key failure-key))
                (into base (into deadline-key
                                 (into first-fetch-key failure-key)))
                (into base (into deadline-key
                                 (into first-fetch-key
                                       (into acknowledgement-key failure-key))))]
      [])))

(defn- validate-attempt! [raw-record]
  (when-not (map? raw-record)
    (spool/fail! "Guidance attempt must be an object" {}))
  (let [record (strict-json/canonical-data raw-record)
        attempt (get record "attempt")
        invocation (get record "invocation")
        transport (get record "transport")
        state (get record "state")
        started-at (parse-instant! (get record "started-at")
                                   "Guidance attempt start")]
    (when-not (pos-int? attempt)
      (spool/fail! "Guidance attempt number must be positive" {}))
    (when-not (nonblank? invocation)
      (spool/fail! "Guidance attempt invocation is invalid" {}))
    (case transport
      "legacy"
      (when-not (and (= "not-required" state)
                     (closed? record base-attempt-keys))
        (spool/fail! "Legacy guidance attempt is malformed" {}))

      "native-v1"
      (do
        (when-not (some #(= (set (keys (dissoc record history/retirement-key))) %)
                        (allowed-native-keys state))
          (spool/fail! "Native guidance attempt is malformed"
                       {:state state}))
        (when-not (and (sha? (get record "bundle-sha256"))
                       (sha? (get record "capability-sha256")))
          (spool/fail! "Native guidance attempt digests are malformed" {}))
        (when-not (and (contains? #{"codex" "pi"} (get record "harness"))
                       (contains? #{"headless" "interactive"}
                                  (get record "mode")))
          (spool/fail! "Native guidance attempt origin is malformed" {}))
        (validate-time-order! record started-at)
        (when (= "failed" state)
          (let [failure (get record "failure")]
            (valid-failure! failure)
            (when-not (contains? record "deadline-at")
              (when-not (= "preflight" (get failure "stage"))
                (spool/fail!
                 "Only no-launch preflight failure may omit its deadline"
                 {}))
              (valid-no-launch! record)))))

      (spool/fail! "Guidance attempt transport is invalid"
                   {:transport transport}))
    record))

(defn- current-record? [run record]
  (and (= (attribute run :harness/attempt) (get record "attempt"))
       (= (attribute run :harness/invocation) (get record "invocation"))))

(defn- validate-attempts! [run raw-attempts]
  (when-not (vector? raw-attempts)
    (spool/fail! "Guidance attempts must be a vector" {}))
  (let [attempts (mapv validate-attempt! raw-attempts)
        numbers (mapv #(get % "attempt") attempts)
        invocations (mapv #(get % "invocation") attempts)]
    (when-not (and (= numbers (vec (sort numbers)))
                   (= (count numbers) (count (distinct numbers))))
      (spool/fail! "Guidance attempts are unordered or duplicated"
                   {:attempts numbers}))
    (when-not (= (count invocations) (count (distinct invocations)))
      (spool/fail! "Guidance attempt invocations are duplicated" {}))
    (doseq [record attempts
            :when (= "native-v1" (get record "transport"))]
      (if (current-record? run record)
        (when (contains? record history/retirement-key)
          (spool/fail! "Current guidance attempt contains retired evidence" {}))
        (history/validate-retired! run record)))
    attempts))

(defn- values [run]
  (into {} (map (fn [key] [key (attribute run key)])) attribute-keys))

(defn- present-keys [run]
  (let [attributes (:attributes run)]
    (into #{} (filter #(contains? attributes %)) attribute-keys)))

(defn- workspace! [run]
  (let [workspace (get-in (meta run)
                          [validation-context-key :canonical-workspace])]
    (when-not (nonblank? workspace)
      (spool/fail! "Guidance validation requires its canonical workspace" {}))
    workspace))

(defn- require-common! [representation present]
  (let [required #{:harness/guidance-version
                   :harness/guidance-transport
                   :harness/guidance-context-template
                   :harness/guidance-context
                   :harness/guidance-bundle-sha256
                   :harness/guidance-attempts}]
    (when-not (every? present required)
      (spool/fail! "Guidance representation is incomplete" {}))
    (when-not (= 1 (:harness/guidance-version representation))
      (spool/fail! "Guidance representation version is invalid" {}))
    (when-not (contains? #{"legacy" "native-v1"}
                         (:harness/guidance-transport representation))
      (spool/fail! "Guidance representation transport is invalid" {}))))

(defn- validate-native!
  [run representation present workspace context-document]
  (when-not (and (contains? present :harness/guidance-capability)
                 (contains? present :harness/guidance-capability-sha256))
    (spool/fail! "Native guidance capability representation is incomplete" {}))
  (let [harness (attribute run :harness/harness)
        capability-document
        (capability/validate-document!
         (:harness/guidance-capability representation) harness)
        digest (:harness/guidance-capability-sha256 representation)
        rendered (context/rendered (:id run) workspace context-document)]
    (when-not (= digest (strict-json/canonical-sha256 capability-document))
      (spool/fail! "Native guidance capability digest does not match" {}))
    (when-not (= (get provider-context-limits harness)
                 (get capability-document "max-context-bytes"))
      (spool/fail! "Native guidance capability context limit is invalid" {}))
    (when (> (strict-json/utf8-bytes rendered)
             (get capability-document "max-context-bytes"))
      (spool/fail! "Frozen managed guidance exceeds its host limit" {}))
    capability-document))

(defn- validate-current-first-fetch! [run record]
  (when-let [provenance (get record "first-fetch")]
    (when-not (and (= "true" (attribute run :harness/native-attached))
                   (= (get record "attempt")
                      (attribute run :harness/native-attachment-attempt))
                   (= (get record "invocation")
                      (attribute run :harness/native-attachment-invocation))
                   (= "managed-startup"
                      (attribute run :harness/native-attachment-source))
                   (= (get provenance "native-session-id")
                      (attribute run :harness/session-id))
                   (= (get provenance "fetched-at")
                      (attribute run :harness/native-attached-at)))
      (spool/fail! "Guidance first fetch does not match its attachment" {}))))

(defn- validate-no-launch-evidence! [run record]
  (when (contains? record "no-launch")
    (let [attempt (get record "attempt")
          invocation (get record "invocation")
          process-key (str (:id run) "/attempt-" attempt)
          contradictions
          (cond-> []
            (and (= "true" (attribute run :harness/native-attached))
                 (= attempt
                    (attribute run :harness/native-attachment-attempt))
                 (= invocation
                    (attribute run :harness/native-attachment-invocation)))
            (conj "attachment")

            (= invocation (attribute run :harness/completion-owner-invocation))
            (conj "completion-owner-custody")

            (= invocation (attribute run :harness/provider-invocation))
            (conj "provider-launch")

            (and (= process-key (attribute run :harness/process-key))
                 (some? (attribute run :harness/process-handle)))
            (conj "process-custody")

            (or (= "process-exit" (attribute run :harness/settlement))
                (some? (attribute run :harness/exit-code)))
            (conj "process-exit"))]
      (when (seq contradictions)
        (spool/fail! "Guidance no-launch provenance conflicts with launch evidence"
                     {:evidence contradictions})))))

(defn- validate-current!
  [run representation attempts]
  (let [attempt (attribute run :harness/attempt)
        invocation (attribute run :harness/invocation)
        current (peek attempts)]
    (if-not current
      (when (or attempt invocation)
        (spool/fail! "Guidance current attempt has no history" {}))
      (do
        (when-not (= attempt (get current "attempt"))
          (spool/fail! "Guidance current attempt does not name its history" {}))
        (validate-no-launch-evidence! run current)
        (when invocation
          (when-not (= invocation (get current "invocation"))
            (spool/fail! "Guidance current invocation does not name its history"
                         {}))
          (when-not (= (attribute run :harness/started-at)
                       (get current "started-at"))
            (spool/fail! "Guidance current start timestamp does not match" {}))
          (when-not (= (:harness/guidance-transport representation)
                       (get current "transport"))
            (spool/fail! "Guidance current transport does not match selection"
                         {}))
          (when (= "native-v1" (get current "transport"))
            (when-not (and
                       (= (attribute run :harness/harness)
                          (get current "harness"))
                       (= (attribute run :harness/mode) (get current "mode"))
                       (= (:harness/guidance-bundle-sha256 representation)
                          (get current "bundle-sha256"))
                       (= (:harness/guidance-capability-sha256 representation)
                          (get current "capability-sha256")))
              (spool/fail! "Guidance current native selection does not match"
                           {}))
            (validate-current-first-fetch! run current)))))))

(defn- validate-versioned! [run representation present]
  (require-common! representation present)
  (let [transport (:harness/guidance-transport representation)
        workspace (workspace! run)
        {:keys [template context]}
        (context/validate-pair!
         (:id run) (attribute run :identity/id)
         (:harness/guidance-context-template representation)
         (:harness/guidance-context representation))
        _ (when-not (= (attribute run :identity/prompt)
                       (get template "identity-instruction"))
            (spool/fail! "Frozen guidance identity does not match the run"
                         {}))
        bundle-digest (:harness/guidance-bundle-sha256 representation)
        attempts (validate-attempts!
                  run (:harness/guidance-attempts representation))]
    (when-not (and (sha? bundle-digest)
                   (= bundle-digest
                      (context/bundle-sha256 (:id run) workspace context)))
      (spool/fail! "Frozen guidance bundle digest does not match" {}))
    (let [capability-document
          (case transport
            "legacy"
            (do
              (when (or (contains? present :harness/guidance-capability)
                        (contains? present
                                   :harness/guidance-capability-sha256))
                (spool/fail! "Legacy guidance contains native capability data"
                             {}))
              nil)
            "native-v1"
            (validate-native! run representation present workspace context))]
      (validate-current! run representation attempts)
      {:versioned? true
       :transport transport
       :template template
       :context context
       :capability capability-document
       :attempts attempts})))

(defn- corrupt! [run representation present error]
  (spool/fail! "Harness run has corrupt partial guidance metadata"
               {:run-id (:id run)
                :version (:harness/guidance-version representation)
                :transport (:harness/guidance-transport representation)
                :missing (->> attribute-keys (remove present) vec)
                :reason (ex-message error)}))

(defn validate!
  "Discriminate and validate one complete durable guidance representation.

  Wholly absent metadata is historical unversioned legacy. Every versioned
  representation is validated against the process-local canonical workspace;
  malformed data is rejected without normalization or durable repair."
  [run]
  (let [representation (values run)
        present (present-keys run)]
    (if (empty? present)
      {:versioned? false :transport "legacy" :attempts []}
      (try
        (validate-versioned! run representation present)
        (catch Throwable error
          (corrupt! run representation present error))))))

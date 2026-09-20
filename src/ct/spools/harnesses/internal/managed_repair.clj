(ns ct.spools.harnesses.internal.managed-repair
  "Explicit conversion of one completed legacy Codex native binding."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-identity :as managed-identity]
            [ct.spools.harnesses.internal.managed-startup :as managed]
            [millhouse.spools.identity :as identity]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(def ^:private repair-request-keys
  #{:run-id :identity :native-session-id})

(s/def ::runtime map?)
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::run-id ::non-blank-string)
(s/def ::identity ::non-blank-string)
(s/def ::native-session-id ::non-blank-string)
(s/def ::repair-request
  (s/and (s/keys :req-un [::run-id ::identity ::native-session-id])
         #(every? repair-request-keys (keys %))))

(defn- require-run [rt id]
  (let [run (or (weaver/show rt id)
                (fail! "Managed startup run not found" {:run-id id}))]
    (when-not (= "true" (attr-get run :harness/run))
      (fail! "Managed startup target is not a harness run" {:run-id id}))
    run))

(defn- performed-run? [rt identity-strand run-id]
  (boolean
   (some #(= run-id (:to_strand_id %))
         (graph/outgoing-edges rt [(:id identity-strand)] "performed"))))

(defn- identities-for-native-session
  [rt harness native-session-id]
  (filterv #(and (= "true" (attr-get % :identity/session))
                 (= harness (attr-get % :identity/harness))
                 (= native-session-id
                    (attr-get % :identity/native-session-id)))
           (weaver/list rt)))

(defn- reserving-runs [rt attribute-name value]
  (filterv #(and (= "true" (attr-get % :harness/run))
                 (= "true" (attr-get % :harness/published))
                 (life/reserving? %))
           (weaver/list rt [:= [:attr attribute-name] value] {})))

(defn repair!
  "Repair one explicitly named completed legacy Codex binding.

  The caller supplies the run ID, friendly identity, and already-recorded native
  session ID. Repair refuses reservations, active writers, occupied sessions,
  mismatched provenance, and non-terminal or unusable runs. It never scans for
  candidates or chooses an identity on the caller's behalf."
  [rt {:keys [run-id identity native-session-id] :as request}]
  (require-valid! ::runtime rt "repair! requires a Weaver runtime")
  (require-valid! ::repair-request request
                  "repair! requires an explicit legacy repair request")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (managed-identity/with-identity-guard
      rt
      (fn []
        (let [run (require-run rt run-id)
              identity-strand (identity/current rt identity)
              harness (attr-get run :harness/harness)
              recorded-session (attr-get run :harness/session-id)
              run-reservation (attr-get run :identity/reservation-id)
              repair-replay? (= "legacy-repair"
                                (attr-get run
                                          :harness/native-attachment-source))
              identity-reservation
              (attr-get identity-strand :identity/reservation-id)
              identity-state
              (attr-get identity-strand :identity/reservation-state)
              identity-native
              (attr-get identity-strand :identity/native-session-id)
              occupied (identities-for-native-session
                        rt harness native-session-id)
              session-writers (remove #(= run-id (:id %))
                                      (reserving-runs
                                       rt "harness/session-id" native-session-id))
              target (attr-get run :harness/target)
              target-writers (when target
                               (remove #(= run-id (:id %))
                                       (reserving-runs
                                        rt "harness/target" target)))]
          (when-not (= "codex" harness)
            (fail! "Legacy native repair applies only to Codex"
                   {:run-id run-id :harness harness}))
          (when-not (= harness (attr-get identity-strand :identity/harness))
            (fail! "Legacy repair identity belongs to another harness"
                   {:run-id run-id
                    :identity identity
                    :expected harness
                    :actual (attr-get identity-strand :identity/harness)}))
          (when-not (and (string? identity-native)
                         (not (str/blank? identity-native)))
            (fail! "Legacy repair identity has no provisional native binding"
                   {:run-id run-id :identity identity}))
          (when-not (and (life/terminal? run) (life/settled? run)
                         (= "true" (attr-get run :harness/session-usable)))
            (fail! "Legacy native repair requires a settled usable run"
                   {:run-id run-id
                    :status (attr-get run :harness/status)
                    :settled (attr-get run :harness/settled)
                    :session-usable
                    (attr-get run :harness/session-usable)}))
          (when-not (= identity (attr-get run :identity/id))
            (fail! "Legacy repair identity does not match the run"
                   {:run-id run-id
                    :expected (attr-get run :identity/id)
                    :actual identity}))
          (when-not (= native-session-id recorded-session)
            (fail! "Legacy repair session does not match recorded run evidence"
                   {:run-id run-id
                    :expected recorded-session
                    :actual native-session-id}))
          (when-not (performed-run? rt identity-strand run-id)
            (fail! "Legacy repair identity did not perform the run"
                   {:run-id run-id :identity identity}))
          (when (and run-reservation (not repair-replay?))
            (fail! "Native repair applies only to legacy Codex runs"
                   {:run-id run-id
                    :native-attachment-source
                    (attr-get run :harness/native-attachment-source)}))
          (when (and run-reservation
                     (not= run-reservation identity-reservation))
            (fail! "Legacy repair run and identity reservations conflict"
                   {:run-id run-id
                    :run-reservation run-reservation
                    :identity-reservation identity-reservation}))
          (when (and identity-reservation
                     (not (and (= "attached" identity-state)
                               (= native-session-id identity-native))))
            (fail! "Legacy repair identity reservation is inconsistent"
                   {:run-id run-id
                    :reservation-id identity-reservation
                    :state identity-state
                    :native-session-id identity-native}))
          (when (and (nil? identity-reservation) identity-state)
            (fail! "Legacy repair identity has reservation state without a token"
                   {:run-id run-id :state identity-state}))
          (when (some #(not= (:id identity-strand) (:id %)) occupied)
            (fail! "Legacy repair native session is occupied"
                   {:run-id run-id
                    :native-session-id native-session-id
                    :identities
                    (mapv #(attr-get % :identity/id) occupied)}))
          (when (seq session-writers)
            (fail! "Legacy repair native session has an active writer"
                   {:run-id run-id
                    :native-session-id native-session-id
                    :runs (mapv :id session-writers)}))
          (when (seq target-writers)
            (fail! "Legacy repair target has an active writer"
                   {:run-id run-id :target target
                    :runs (mapv :id target-writers)}))
          (let [reservation-id (or identity-reservation
                                   (str (java.util.UUID/randomUUID)))
                {:keys [run]}
                (if repair-replay?
                  {:run run}
                  (managed-identity/persist-attachment!
                   rt
                   {:identity-strand identity-strand
                    :run run
                    :identity-attributes
                    (when-not identity-reservation
                      {:identity/native-session-id native-session-id
                       :identity/reservation-id reservation-id
                       :identity/reservation-state "attached"})
                    :run-attributes
                    {:identity/reservation-id reservation-id
                     :harness/provisional-session-id
                     (or (attr-get run :harness/provisional-session-id)
                         identity-native)
                     :harness/native-attached "true"
                     :harness/native-attachment-source "legacy-repair"
                     :harness/native-attached-at
                     (str (java.time.Instant/now))
                     :harness/native-attachment-attempt
                     (attr-get run :harness/attempt)
                     :harness/native-attachment-invocation
                     (attr-get run :harness/invocation)}}))]
            {:schema managed/managed-context-schema
             :run-id run-id
             :harness harness
             :native-session-id native-session-id
             :identity identity
             :strand-id (:id identity-strand)
             :result (if run-reservation "recovered" "repaired")
             :instruction (managed/identity-instruction identity)
             :context {:schema managed/managed-context-schema
                       :identity-instruction
                       (managed/identity-instruction identity)
                       :appended-system-prompts
                       (or (attr-get run
                                     :harness/appended-system-prompts)
                           [])}}))))))

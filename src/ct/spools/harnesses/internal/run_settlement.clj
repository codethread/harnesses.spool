(ns ct.spools.harnesses.internal.run-settlement
  "Durable late-outcome settlement and attachment transitions."
  (:require [clojure.spec.alpha :as s]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-startup :as managed]
            [ct.spools.harnesses.internal.runs :as runs]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- require-valid! [spec value message]
  (if (s/valid? spec value)
    value
    (fail! message {:explain (s/explain-data spec value)})))

(defn settle-outcome!
  "Record provider outcome and settlement for an already terminal run.

  Custody evidence is persisted independently before optional
  reservation-backed Codex/Pi attachment. An attachment failure therefore
  cannot erase proof that the provider process settled. Existing hook-confirmed
  session evidence is never replaced by an unobserved interactive outcome.

  Positive legacy evidence is validated before the custody update so malformed
  callbacks write nothing. A fenced failed outcome with no usable session has
  no identity evidence to attach, so its custody settlement remains recordable
  even when the historical identity is damaged. Valid pre-reservation runs keep
  their historical representation without invented attachment evidence."
  [rt id outcome evidence]
  (require-valid! :ct.spools.harnesses/runtime rt "settle-outcome! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/id id "settle-outcome! requires a run id")
  (require-valid! :ct.spools.harnesses/outcome outcome "settle-outcome! requires a valid outcome")
  (require-valid! :ct.spools.harnesses/evidence evidence "settle-outcome! requires evidence")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)
          invocation (:invocation outcome)]
      (when (= "external" (attr-get run :harness/ownership))
        (fail! "Harnesses does not own external session settlement" {:id id}))
      (when-not (life/terminal? run)
        (fail! "Only a terminal harness run may receive late outcome evidence"
               {:id id :status (life/status run)}))
      (managed/validate-legacy-outcome! rt run outcome)
      (when (and (attr-get run :harness/invocation)
                 (not= invocation (attr-get run :harness/invocation)))
        (fail! "Late harness outcome has a missing or stale invocation"
               {:id id
                :expected (attr-get run :harness/invocation)
                :actual invocation}))
      (let [managed? (managed/managed-harness?
                      (attr-get run :harness/harness))
            attached? (= "true" (attr-get run :harness/native-attached))
            session-id (if attached?
                         (attr-get run :harness/session-id)
                         (or (:session-id outcome)
                             (attr-get run :harness/session-id)))
            native-mismatch? (and (= "codex" (attr-get run :harness/harness))
                                  (or (not= invocation
                                            (attr-get run :harness/native-attachment-invocation))
                                      (and (:session-id outcome)
                                           (not= session-id (:session-id outcome)))))
            usable? (and (not native-mismatch?)
                         (or (= "true" (attr-get run :harness/session-usable))
                             (and (or (not managed?) attached?)
                                  (true? (:session-usable outcome)))))
            settled (require-valid!
                     :ct.spools.harnesses/strand
                     (weaver/update!
                      rt id
                      {:attributes
                       (merge
                        {:harness/exit-code (:exit-code outcome)
                         :harness/result (:result outcome)
                         :harness/session-id session-id
                         :harness/session-usable (if usable? "true" "false")
                         :harness/error
                         (or (when native-mismatch?
                               "Codex native startup missing or inconsistent with the current invocation")
                             (attr-get run :harness/error)
                             (when (= :failed (:status outcome))
                               (or (:error outcome)
                                   "Harness process failed")))
                         :harness/settled (if (:settled evidence) "true" "false")
                         :harness/settlement (:settlement evidence)}
                        (when-let [gap (:gap evidence)]
                          {:harness/settlement-gap gap}))})
                     "settle-outcome! produced an invalid run strand")
            bootstrap-failed?
            (and (guidance/native? run)
                 (= "bootstrap" (life/substatus run)))
            attached-result (when-not bootstrap-failed?
                              (managed/attach-outcome! rt settled outcome))]
        (if attached-result
          (require-valid!
           :ct.spools.harnesses/strand
           (weaver/update!
            rt id
            {:attributes
             {:harness/session-usable
              (if (true? (:session-usable outcome)) "true" "false")}})
           "settle-outcome! produced invalid attached session evidence")
          settled)))))

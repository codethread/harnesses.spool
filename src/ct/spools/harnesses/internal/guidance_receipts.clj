(ns ct.spools.harnesses.internal.guidance-receipts
  "Attempt-scoped native guidance acknowledgement and failure receipts."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :as spool]
            [millstrand.api.weaver.alpha :as weaver]))

(def guidance-receipt-schema
  "Version identifying an adapter handoff or failure receipt."
  "millstrand.agent-guidance-receipt/v1")

(def guidance-receipt-result-schema
  "Version identifying the durable result of a guidance receipt."
  "millstrand.agent-guidance-receipt-result/v1")

(def ^:private receipt-common-keys
  #{"schema" "run-id" "attempt" "invocation" "harness"
    "native-session-id" "transport" "bundle-sha256"
    "capability-sha256" "outcome"})
(def ^:private failure-extra-keys #{"stage" "code" "diagnostic"})
(def ^:private failure-stages
  #{"preflight" "startup" "validation" "rendering" "handoff"})
(def ^:private metadata-limit (* 64 1024))

(defn- normalize-document [value label]
  (cond
    (string? value) (strict-json/parse-object! value metadata-limit label)
    (map? value)
    (reduce-kv
     (fn [result key item]
       (let [key (name key)]
         (when (contains? result key)
           (spool/fail! (str label " contains duplicate normalized keys")
                        {:key key}))
         (assoc result key item)))
     {}
     value)
    :else (spool/fail! (str label " must be a JSON object") {:value value})))

(defn- attempt-record [run]
  (guidance/current-attempt run))

(defn- require-run [rt id]
  (let [run (or (weaver/show rt id)
                (spool/fail! "Guidance receipt run was not found" {:run-id id}))]
    (when-not (= "true" (spool/attr-get run :harness/run))
      (spool/fail! "Guidance receipt target is not a harness run" {:run-id id}))
    (guidance/validation-run rt run)))

(defn- receipt! [value expected-outcome]
  (let [receipt (normalize-document value "Guidance receipt")
        keys (set (keys receipt))
        expected-keys (if (= "failed" expected-outcome)
                        (into receipt-common-keys failure-extra-keys)
                        receipt-common-keys)
        valid-keys? (if (= "failed" expected-outcome)
                      (or (= expected-keys keys)
                          (= (disj expected-keys "native-session-id") keys))
                      (= expected-keys keys))]
    (when-not valid-keys?
      (spool/fail! "Guidance receipt has invalid keys"
                   {:required (sort expected-keys) :actual (sort keys)}))
    (when-not (and (= guidance-receipt-schema (get receipt "schema"))
                   (= "native-v1" (get receipt "transport"))
                   (= expected-outcome (get receipt "outcome"))
                   (pos-int? (get receipt "attempt"))
                   (every? #(and (string? (get receipt %))
                                 (not (str/blank? (get receipt %))))
                           ["run-id" "invocation" "harness"
                            "bundle-sha256" "capability-sha256"]))
      (spool/fail! "Guidance receipt is malformed" {:receipt receipt}))
    (if (= "failed" expected-outcome)
      (when-not (and (contains? failure-stages (get receipt "stage"))
                     (not (str/blank? (get receipt "code")))
                     (not (str/blank? (get receipt "diagnostic"))))
        (spool/fail! "Guidance failure receipt is malformed" {}))
      (when-not (and (= "adapter-handoff" expected-outcome)
                     (not (str/blank? (get receipt "native-session-id"))))
        (spool/fail! "Guidance acknowledgement is malformed" {})))
    receipt))

(defn- result [receipt disposition state]
  (merge {:schema guidance-receipt-result-schema
          :result disposition
          :state state}
         (select-keys receipt ["run-id" "attempt" "invocation" "harness"
                               "native-session-id" "transport"
                               "bundle-sha256" "capability-sha256"])))

(defn- stale? [run receipt]
  (or (not= (get receipt "attempt")
            (spool/attr-get run :harness/attempt))
      (not= (get receipt "invocation")
            (spool/attr-get run :harness/invocation))
      (and (life/terminal? run)
           (not= "bootstrap" (life/substatus run)))))

(defn- validate-receipt-fences! [run receipt]
  (doseq [[label expected actual]
          [["provider" (spool/attr-get run :harness/harness)
            (get receipt "harness")]
           ["bundle digest" (spool/attr-get run
                                            :harness/guidance-bundle-sha256)
            (get receipt "bundle-sha256")]
           ["capability digest"
            (spool/attr-get run :harness/guidance-capability-sha256)
            (get receipt "capability-sha256")]]]
    (when-not (= expected actual)
      (spool/fail! (str "Guidance receipt " label " does not match the run")
                   {:run-id (:id run) :expected expected :actual actual})))
  (when-let [attached (when (= "true" (spool/attr-get run
                                                      :harness/native-attached))
                        (spool/attr-get run :harness/session-id))]
    (when (and (get receipt "native-session-id")
               (not= attached (get receipt "native-session-id")))
      (spool/fail! "Guidance receipt native session does not match the run"
                   {:run-id (:id run) :expected attached
                    :actual (get receipt "native-session-id")}))))

(defn- update-record [run record updated]
  {:harness/guidance-attempts
   (mapv #(if (= record %) updated %)
         (guidance/attempt-records run))})

(defn- failure-attributes [run record failure stop-reason]
  (merge
   (update-record run record (assoc record "state" "failed"
                                    "failure" failure))
   {:harness/status "failed"
    :harness/substatus "bootstrap"
    :harness/error (get failure "diagnostic")
    :harness/session-usable "false"
    :harness/stop-requested-at (life/now)
    :harness/stop-reason stop-reason}))

(defn- expire-at! [rt run now]
  (let [record (attempt-record run)]
    (when-not record
      (spool/fail! "Native guidance run has no current attempt record"
                   {:run-id (:id run)}))
    (if-not (guidance/deadline-expired? run record now)
      run
      (let [diagnostic
            (str "Native guidance handoff deadline expired for run "
                 (:id run) " attempt " (get record "attempt")
                 ". Repair the reviewed adapter/configuration or "
                 "explicitly submit legacy work after settlement.")
            failure {"stage" "handoff"
                     "code" "acknowledgement-timeout"
                     "diagnostic" diagnostic}]
        (guidance/validation-run
         rt
         (weaver/update!
          rt (:id run)
          {:attributes
           (failure-attributes
            run record failure "native guidance handoff timed out")}))))))

(defn expire!
  "Fail an overdue exact-current native handoff.

  The supplied run identifies the originating attempt only. The transition
  reloads and rechecks durable state while holding the publication lock, so an
  obsolete timer cannot overwrite acknowledgement, completion, or retry."
  [rt originating-run]
  (let [origin-attempt (spool/attr-get originating-run :harness/attempt)
        origin-invocation (spool/attr-get originating-run :harness/invocation)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (let [run (require-run rt (:id originating-run))]
        (if-not (and (= "running" (life/status run))
                     (= origin-attempt
                        (spool/attr-get run :harness/attempt))
                     (= origin-invocation
                        (spool/attr-get run :harness/invocation))
                     (guidance/native? run))
          run
          (expire-at! rt run (java.time.Instant/now)))))))

(defn- validate-prospective! [rt run attributes]
  (guidance/validate-representation!
   (guidance/validation-run rt (update run :attributes merge attributes)))
  attributes)

(defn acknowledge!
  "Record or replay one exact adapter-handoff acknowledgement."
  [rt value]
  (let [receipt (receipt! value "adapter-handoff")]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (let [run (require-run rt (get receipt "run-id"))]
        (if (stale? run receipt)
          (result receipt "ignored" (or (some-> (attempt-record run)
                                                (get "state"))
                                        "not-required"))
          (do
            (when-not (guidance/native? run)
              (spool/fail! "Guidance acknowledgement targets a legacy run"
                           {:run-id (:id run)}))
            (validate-receipt-fences! run receipt)
            (let [transition-at (life/now)
                  run (expire-at! rt run
                                  (java.time.Instant/parse transition-at))
                  record (attempt-record run)
                  state (get record "state")]
              (case state
                "failed" (result receipt "ignored" state)
                "acknowledged" (result receipt "replayed" state)
                "fetched"
                (do
                  (weaver/update!
                   rt (:id run)
                   {:attributes
                    (validate-prospective!
                     rt run
                     (update-record run record
                                    (assoc record
                                           "state" "acknowledged"
                                           "acknowledged-at" transition-at)))})
                  (result receipt "recorded" "acknowledged"))
                (spool/fail! "Guidance acknowledgement has an invalid state"
                             {:run-id (:id run) :state state})))))))))

(defn fail!
  "Record one exact adapter bootstrap failure and durable stop intent."
  [rt value]
  (let [receipt (receipt! value "failed")]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (let [run (require-run rt (get receipt "run-id"))]
        (if (stale? run receipt)
          (result receipt "ignored" (or (some-> (attempt-record run)
                                                (get "state"))
                                        "not-required"))
          (do
            (when-not (guidance/native? run)
              (spool/fail! "Guidance failure targets a legacy run"
                           {:run-id (:id run)}))
            (validate-receipt-fences! run receipt)
            (let [record (attempt-record run)
                  state (get record "state")
                  failure (select-keys receipt ["stage" "code" "diagnostic"])]
              (cond
                (and (= "failed" state) (= failure (get record "failure")))
                (result receipt "replayed" state)

                (= "failed" state)
                (result receipt "ignored" state)

                (contains? #{"pending" "fetched" "acknowledged"} state)
                (do
                  (weaver/update!
                   rt (:id run)
                   {:attributes
                    (failure-attributes
                     run record failure
                     "native guidance bootstrap failed")})
                  (result receipt "recorded" "failed"))

                :else
                (spool/fail! "Guidance failure has an invalid state"
                             {:run-id (:id run) :state state})))))))))

(defn completion
  "Return provider outcome plus guidance patch enforcing native acknowledgement.

  A negative result while the run is still ready has no launched process and
  needs no attempt receipt. Positive evidence and missing records on active
  attempts remain invalid."
  [run outcome]
  (if-not (guidance/native? run)
    {:outcome outcome}
    (let [record (attempt-record run)
          prelaunch-failure?
          (and (= "ready" (life/status run))
               (nil? (life/invocation run))
               (= :failed (:status outcome))
               (not (true? (:session-usable outcome)))
               (nil? (:session-id outcome)))]
      (cond
        prelaunch-failure? {:outcome outcome}

        (nil? record)
        (spool/fail! "Native guidance completion has no attempt record"
                     {:run-id (:id run)
                      :status (life/status run)
                      :outcome (:status outcome)})

        (and (= "acknowledged" (get record "state"))
             (= "true" (spool/attr-get run :harness/native-attached)))
        {:outcome outcome}

        :else
        (let [diagnostic
              (str "Native guidance bootstrap was not acknowledged for run "
                   (:id run) " attempt "
                   (spool/attr-get run :harness/attempt)
                   ". Repair the reviewed adapter/configuration or explicitly "
                   "submit legacy work after settlement.")
              failure {"stage" "handoff"
                       "code" "missing-acknowledgement"
                       "diagnostic" diagnostic}]
          {:outcome (assoc outcome :status :failed :error diagnostic
                           :session-usable false)
           :evidence {:failure-class "bootstrap"}
           :attributes
           (failure-attributes
            run record failure "native guidance bootstrap failed")})))))

(ns ct.spools.harnesses.internal.managed-legacy
  "Classification and validation for pre-reservation provider runs."
  (:require [clojure.string :as str]
            [millhouse.spools.identity :as identity]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]))

(def ^:private managed-harnesses
  "Providers with the pre-reservation managed representation; both native
  providers cut over, so only historical rows retain this shape."
  #{})

(defn- managed-provider? [run]
  (contains? managed-harnesses (attr-get run :harness/harness)))

(defn managed-run?
  "Return whether `run` has the pre-reservation managed representation.

  Accepted legacy Codex rows have a published identity binding but none of
  the three fields introduced by reservation-backed startup. A partially
  damaged current row retains at least its native attachment or provisional
  session field and must continue through the strict startup-v1 path."
  [run]
  (and (managed-provider? run)
       (= "true" (attr-get run :harness/published))
       (nil? (attr-get run :identity/reservation-id))
       (nil? (attr-get run :harness/native-attached))
       (nil? (attr-get run :harness/provisional-session-id))))

(defn- performed-run? [rt identity-strand run-id]
  (boolean
   (some #(= run-id (:to_strand_id %))
         (graph/outgoing-edges rt [(:id identity-strand)] "performed"))))

(defn- require-binding!
  [rt run]
  (let [friendly-id (attr-get run :identity/id)]
    (when-not (and (string? friendly-id) (not (str/blank? friendly-id)))
      (fail! "Legacy managed run has no identity binding"
             {:run-id (:id run) :identity friendly-id}))
    (let [identity-strand (identity/current rt friendly-id)
          harness (attr-get run :harness/harness)
          identity-harness (attr-get identity-strand :identity/harness)
          native-session-id
          (attr-get identity-strand :identity/native-session-id)
          reservation-id
          (attr-get identity-strand :identity/reservation-id)
          reservation-state
          (attr-get identity-strand :identity/reservation-state)]
      (when-not (= harness identity-harness)
        (fail! "Legacy managed identity belongs to another harness"
               {:run-id (:id run)
                :identity friendly-id
                :expected harness
                :actual identity-harness}))
      (when-not (and (string? native-session-id)
                     (not (str/blank? native-session-id)))
        (fail! "Legacy managed identity has no native session binding"
               {:run-id (:id run) :identity friendly-id}))
      (when (or reservation-id reservation-state)
        (fail! "Legacy managed identity has reservation metadata"
               {:run-id (:id run)
                :identity friendly-id
                :reservation-id reservation-id
                :reservation-state reservation-state}))
      (when-not (performed-run? rt identity-strand (:id run))
        (fail! "Legacy managed identity did not perform the run"
               {:run-id (:id run) :identity friendly-id}))
      identity-strand)))

(defn validate-continuation-request!
  "Validate explicit provider and session intent for a managed continuation.

  Reservation-backed predecessors retain their attached provider and session.
  Pre-reservation predecessors must additionally be genuine exact-binding Pi
  runs. Non-managed providers retain their ordinary continuation path."
  [_rt predecessor requested-harness requested-session-id]
  (when (managed-provider? predecessor)
    (let [expected-harness (attr-get predecessor :harness/harness)
          expected-session-id (attr-get predecessor :harness/session-id)]
      (when-not (= expected-harness requested-harness)
        (fail! "Managed continuation cannot change its provider"
               {:predecessor (:id predecessor)
                :expected expected-harness
                :requested requested-harness}))
      (when-not (and (string? requested-session-id)
                     (not (str/blank? requested-session-id))
                     (= expected-session-id requested-session-id))
        (fail! "Managed continuation requires its exact explicit native session"
               {:predecessor (:id predecessor)
                :expected expected-session-id
                :requested requested-session-id})))
    (when (managed-run? predecessor)
      (fail! "Legacy Codex continuation requires explicit repair" {:predecessor (:id predecessor)})))
  nil)

(defn positive-outcome?
  "Return whether an outcome is done or carries explicit usable-session evidence."
  [{:keys [status session-usable]}]
  (or (= :done (if (keyword? status) status (keyword status)))
      (true? session-usable)))

(defn- require-positive-session-id!
  [run {:keys [session-id] :as outcome}]
  (when (and (managed-run? run) (positive-outcome? outcome)
             (not (and (string? session-id) (not (str/blank? session-id)))))
    (fail! "Positive legacy outcome requires a native session id"
           {:run-id (:id run) :session-id session-id}))
  nil)

(defn require-positive-attempt!
  "Require a durable launch fence before accepting positive legacy evidence.

  Failed prelaunch outcomes without usable session evidence remain valid and do
  not require an attempt."
  [run {:keys [invocation] :as outcome}]
  (require-positive-session-id! run outcome)
  (when (and (managed-run? run) (positive-outcome? outcome))
    (let [attempt (attr-get run :harness/attempt)
          durable-invocation (attr-get run :harness/invocation)]
      (when-not (and (pos-int? attempt)
                     (string? durable-invocation)
                     (not (str/blank? durable-invocation)))
        (fail! "Positive legacy outcome requires a durable launch fence"
               {:run-id (:id run)
                :attempt attempt
                :invocation durable-invocation}))
      (when-not (and (string? invocation) (not (str/blank? invocation)))
        (fail! "Positive legacy outcome requires its invocation token"
               {:run-id (:id run)
                :expected durable-invocation
                :actual invocation}))))
  nil)

(defn require-positive-invocation!
  "Require the supplied invocation to equal a positive legacy outcome's fence."
  [run {:keys [invocation] :as outcome}]
  (require-positive-attempt! run outcome)
  (when (and (managed-run? run) (positive-outcome? outcome))
    (let [durable-invocation (attr-get run :harness/invocation)]
      (when-not (and (string? invocation)
                     (not (str/blank? invocation))
                     (= durable-invocation invocation))
        (fail! "Positive legacy outcome has a missing or stale invocation"
               {:run-id (:id run)
                :expected durable-invocation
                :actual invocation}))))
  nil)

(defn validate-outcome!
  "Validate identity and invocation before accepting positive legacy evidence.

  Failed outcomes without usable session evidence have nothing to attach and do
  not depend on the historical identity remaining valid."
  [rt run outcome]
  (require-positive-session-id! run outcome)
  (when (and (managed-run? run) (positive-outcome? outcome))
    (require-positive-invocation! run outcome)
    (require-binding! rt run))
  nil)

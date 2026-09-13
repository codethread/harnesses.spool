(ns ct.spools.harnesses.internal.managed-legacy
  "Classification and validation for pre-reservation Codex/Pi runs."
  (:require [clojure.string :as str]
            [millhouse.spools.identity :as identity]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(def ^:private managed-harnesses #{"codex" "pi"})

(defn managed-run?
  "Return whether `run` has the pre-reservation managed representation.

  Accepted legacy Codex/Pi rows have a published identity binding but none of
  the three fields introduced by reservation-backed startup. A partially
  damaged current row retains at least its native attachment or provisional
  session field and must continue through the strict startup-v1 path."
  [run]
  (and (contains? managed-harnesses (attr-get run :harness/harness))
       (= "true" (attr-get run :harness/published))
       (nil? (attr-get run :identity/reservation-id))
       (nil? (attr-get run :harness/native-attached))
       (nil? (attr-get run :harness/provisional-session-id))))

(defn- performed-run? [rt identity-strand run-id]
  (boolean
   (some #(= run-id (:to_strand_id %))
         (graph/outgoing-edges rt [(:id identity-strand)] "performed"))))

(defn- identity-record? [strand]
  (= "true" (attr-get strand :identity/session)))

(defn- identities-for-native-session [rt harness native-session-id]
  (filterv #(and (identity-record? %)
                 (= harness (attr-get % :identity/harness))
                 (= native-session-id
                    (attr-get % :identity/native-session-id)))
           (weaver/list rt)))

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

(defn require-pi-continuation!
  "Return the exact identity binding for a safe legacy Pi continuation.

  The run must use the pre-reservation representation and retain one unique Pi
  identity whose native session exactly equals the run's stored session. The
  identity must have performed this run."
  [rt run]
  (when-not (managed-run? run)
    (fail! "Legacy Pi continuation requires a pre-reservation run"
           {:run-id (:id run)}))
  (when-not (= "pi" (attr-get run :harness/harness))
    (fail! "Legacy native continuation applies only to exact-binding Pi runs"
           {:run-id (:id run)
            :harness (attr-get run :harness/harness)}))
  (let [identity-strand (require-binding! rt run)
        run-session-id (attr-get run :harness/session-id)
        native-session-id
        (attr-get identity-strand :identity/native-session-id)
        bindings (when (and (string? run-session-id)
                            (not (str/blank? run-session-id)))
                   (identities-for-native-session rt "pi" run-session-id))]
    (when-not (and (string? run-session-id)
                   (not (str/blank? run-session-id))
                   (= run-session-id native-session-id))
      (fail! "Legacy Pi run session does not match its identity binding"
             {:run-id (:id run)
              :run-session-id run-session-id
              :identity-session-id native-session-id}))
    (when-not (and (= 1 (count bindings))
                   (= (:id identity-strand) (:id (first bindings))))
      (fail! "Legacy Pi native session does not resolve to one identity"
             {:run-id (:id run)
              :session-id run-session-id
              :identities (mapv #(attr-get % :identity/id) bindings)}))
    identity-strand))

(defn validate-continuation-request!
  "Validate raw provider and session intent for a legacy native continuation.

  Current reservation-backed predecessors and non-managed providers are not
  legacy continuations and are left to their ordinary validation paths."
  [rt predecessor requested-harness requested-session-id]
  (when (managed-run? predecessor)
    (require-pi-continuation! rt predecessor)
    (let [expected-session-id (attr-get predecessor :harness/session-id)]
      (when-not (= "pi" requested-harness)
        (fail! "Legacy Pi continuation cannot change its managed provider"
               {:predecessor (:id predecessor)
                :expected "pi"
                :requested requested-harness}))
      (when-not (= expected-session-id requested-session-id)
        (fail! "Legacy Pi continuation requires its exact native session"
               {:predecessor (:id predecessor)
                :expected expected-session-id
                :requested requested-session-id}))))
  nil)

(defn commit-pi-identity!
  "Bind one prevalidated child to its legacy Pi predecessor identity."
  [rt run predecessor caller]
  (require-pi-continuation! rt predecessor)
  (let [binding (identity/bind!
                 rt
                 {:harness "pi"
                  :native-session-id (attr-get predecessor :harness/session-id)
                  :run-id (:id run)
                  :expected-identity (attr-get predecessor :identity/id)})]
    (when (and caller (not= (:id caller) (:strand-id binding)))
      (weaver/update!
       rt (:id caller)
       {:edges [{:type "parent-of" :to (:strand-id binding)}]}))
    (assoc binding :legacy-pi true)))

(defn retry-pi-binding!
  "Validate and return the retained identity for a legacy Pi retry."
  [rt run]
  (require-pi-continuation! rt run)
  {:identity (attr-get run :identity/id)
   :prompt (attr-get run :identity/prompt)
   :legacy-pi true})

(defn require-launch!
  "Validate a pre-reservation Pi continuation before legacy launch transport."
  [rt run]
  (when-not (and (string? (attr-get run :harness/resumes))
                 (not (str/blank? (attr-get run :harness/resumes))))
    (fail! "Legacy Pi launch requires a native continuation"
           {:run-id (:id run)
            :resumes (attr-get run :harness/resumes)}))
  (require-pi-continuation! rt run)
  nil)

(defn positive-outcome?
  "Return whether an outcome is done or carries explicit usable-session evidence."
  [{:keys [status session-usable]}]
  (or (= :done (if (keyword? status) status (keyword status)))
      (true? session-usable)))

(defn- require-usable-session-id!
  [run {:keys [session-id session-usable]}]
  (when (and (managed-run? run) (true? session-usable)
             (not (and (string? session-id) (not (str/blank? session-id)))))
    (fail! "Usable legacy session evidence requires a native session id"
           {:run-id (:id run) :session-id session-id}))
  nil)

(defn require-positive-attempt!
  "Require a durable launch fence before accepting positive legacy evidence.

  Failed prelaunch outcomes without usable session evidence remain valid and do
  not require an attempt."
  [run {:keys [invocation] :as outcome}]
  (require-usable-session-id! run outcome)
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

(defn- require-pi-session-evidence!
  [rt run session-id session-usable]
  (when (and (= "pi" (attr-get run :harness/harness))
             (true? session-usable))
    (let [identity-strand (require-pi-continuation! rt run)
          durable-session-id (attr-get run :harness/session-id)
          identity-session-id
          (attr-get identity-strand :identity/native-session-id)]
      (when-not (= durable-session-id identity-session-id session-id)
        (fail! "Legacy Pi outcome session does not match its durable binding"
               {:run-id (:id run)
                :expected durable-session-id
                :identity-session-id identity-session-id
                :actual session-id}))))
  nil)

(defn validate-outcome!
  "Validate identity and invocation before accepting positive legacy evidence.

  Failed outcomes without usable session evidence have nothing to attach and do
  not depend on the historical identity remaining valid."
  [rt run {:keys [session-id session-usable] :as outcome}]
  (require-usable-session-id! run outcome)
  (when (and (managed-run? run) (positive-outcome? outcome))
    (require-positive-invocation! run outcome)
    (require-binding! rt run)
    (require-pi-session-evidence! rt run session-id session-usable))
  nil)

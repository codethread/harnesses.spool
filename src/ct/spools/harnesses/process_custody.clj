(ns ct.spools.harnesses.process-custody
  "Reconciliation between durable harness runs and Mill process custody."
  (:require [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.process-custody :as custody]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.weaver.alpha :as weaver]))

(defn desired
  "Read active headless harness runs requiring process inspection."
  [{:keys [runtime]}]
  (->> (weaver/list runtime
                    [:and [:= :state "active"]
                     [:= [:attr "harness/run"] "true"]
                     [:= [:attr "harness/mode"] "headless"]
                     [:= [:attr "harness/phase"] "running"]]
                    {})
       (remove #(execution/launch-in-flight? runtime %))
       (mapv #(select-keys % [:id :state :attributes]))))

(defn actual
  "Read owner-scoped process facts for the desired harness runs."
  [{:keys [runtime desired]}]
  (when (seq desired)
    (custody/list-owned runtime)))

(defn apply!
  "Reconcile durable harness runs against Mill process facts."
  [{:keys [runtime desired actual]}]
  (when (seq desired)
    (execution/inspect-owned! runtime))
  {:reconciled :harness-process-custody
   :runs (count desired)
   :facts (count actual)
   :status :applied})

(defn remove!
  "Leave Mill-owned process facts intact when reconciliation is removed."
  [_context]
  {:reconciled :harness-process-custody :status :removed})

(lifecycle/defreconcile harness-process-custody
  "Reconcile active headless harness runs with Mill process custody."
  {:read-desired 'ct.spools.harnesses.process-custody/desired
   :read-actual 'ct.spools.harnesses.process-custody/actual
   :apply 'ct.spools.harnesses.process-custody/apply!
   :on-removed 'ct.spools.harnesses.process-custody/remove!
   :trigger-kinds #{}
   :after #{:harness-execution-runtime}})

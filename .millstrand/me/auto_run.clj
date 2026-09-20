(ns harnesses.auto-run
  "Activate bounded automatic pickup through Harnesses' full-land workflow."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [ct.spools.codethread.auto-run :as auto-run]
            [ct.spools.codethread.auto-run-worktree]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]))

(millstrand/use-op! auto-run/auto-run)

(def ^:private config-kind :harnesses.auto-run/config)
(def ^:private desired-policy
  {:seat "sol"
   :effort "high"
   :workflow "auto-full-land"
   :workflows #{"auto-full-land" "auto-human-review"}
   :prepare 'ct.spools.codethread.auto-run-worktree/prepare!
   :enabled? true
   :max-running 2
   :interval-ms 15000})

(s/def ::config map?)

(defn- config-kinds [runtime]
  (runtime/spool-state runtime ::config-kinds registry/registry))

(runtime/collect-kind! ::config-kinds
                       {:id config-kind
                        :entry-spec ::config
                        :binding-moment :auto-run/configure})
(runtime/collect-entry! config-kind :repository desired-policy)

(defn desired-config
  "Return the checked-in automatic delivery configuration."
  [{:keys [runtime]}]
  (assoc (get (registry/effective (config-kinds runtime) config-kind)
              :repository)
         :repo (.getCanonicalPath
                (.getParentFile
                 (io/file (get-in runtime [:metadata :config-dir]))))))

(defn actual-config
  "Return the dispatcher's active automatic delivery configuration."
  [{:keys [runtime]}]
  (when-let [config (:config (auto-run/status runtime))]
    (cond-> (-> config
                (update :prepare symbol)
                (update :workflows set))
      (:start-params config) (update :start-params symbol))))

(defn reconcile-config!
  "Apply the checked-in configuration when it differs from the dispatcher."
  [{:keys [runtime desired actual]}]
  (if (= desired actual)
    {:changed? false :config actual}
    {:changed? true :config (auto-run/configure! runtime desired)}))

(defn remove-config!
  "Stop new admissions when the configuration module is removed."
  [{:keys [runtime]}]
  (auto-run/stop! runtime))

(lifecycle/defreconcile! auto-run-dispatcher
  "Keep repository automatic delivery aligned with checked-in policy."
  {:read-desired 'harnesses.auto-run/desired-config
   :read-actual 'harnesses.auto-run/actual-config
   :apply 'harnesses.auto-run/reconcile-config!
   :on-removed 'harnesses.auto-run/remove-config!
   :trigger-kinds #{config-kind}})

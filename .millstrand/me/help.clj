(ns me.help
  "Own the default Batteries help-transform election for this workspace."
  (:require [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.runtime.help-transform.alpha :as help-transform]))

(defn reconcile-help-transform
  "Register Batteries' builtin help transform for this runtime."
  [{:keys [runtime]}]
  (help-transform/register-builtin! runtime)
  {:registered :help-transform})

(defn close-help-transform!
  "Unregister Batteries' builtin help transform for this runtime."
  [{:keys [runtime]}]
  (help-transform/unregister-default-help-transform! runtime 'millstrand.spools.batteries)
  {:unregistered :help-transform})

(lifecycle/defresource! batteries-help-transform
  "Own this world's batteries help-transform election for the module lifetime."
  {:open 'me.help/reconcile-help-transform
   :close 'me.help/close-help-transform!})

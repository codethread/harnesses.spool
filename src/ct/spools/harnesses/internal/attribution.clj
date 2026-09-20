(ns ct.spools.harnesses.internal.attribution
  "Durable actor evidence for harness run mutations."
  (:require [millstrand.api.batch.alpha :as batch]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]))

(defn update-with-action!
  "Update `run-id`, atomically appending actor evidence when an actor is supplied.

  The immutable note preserves each mutation actor independently of mutable run
  attributes. Identity reconciliation later projects the canonical
  `identity/by-identity` value into an `attributed` edge. The run transition,
  note birth, and `notes` edge share one transaction so none can persist without
  the others. Unattributed mutations retain the ordinary Weaver update path."
  [rt run-id patch action by-identity attributes]
  (if-not by-identity
    (weaver/update! rt run-id patch)
    (let [text (str "Agent run " action ".")
          result
          (batch/apply!
           rt
           {:refs {:run run-id}
            :strands [(assoc patch :ref :run)
                      {:ref :note
                       :title text
                       :state "closed"
                       :attributes
                       (merge {:note/text text
                               :note/at (str (runtime/now rt))
                               :identity/by-identity by-identity
                               :harness/action action}
                              attributes)}]
            :edges [{:op :upsert
                     :from :note
                     :to :run
                     :type "notes"}]
            :burn []})]
      (or (some (fn [{:keys [ref after]}]
                  (when (= :run ref) after))
                (:updated result))
          (throw (ex-info "Actor-attributed run batch did not update its run"
                          {:run-id run-id :action action}))))))

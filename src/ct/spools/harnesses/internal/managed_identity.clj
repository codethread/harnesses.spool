(ns ct.spools.harnesses.internal.managed-identity
  "Atomic identity attachment and managed run evidence persistence."
  (:require [millstrand.api.batch.alpha :as batch]
            [millstrand.api.spool.alpha :refer [fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn with-identity-guard
  "Call `f` while holding the pinned Identity implementation's workspace guard."
  [rt f]
  (let [guard (ns-resolve 'millhouse.spools.identity 'with-identity-guard)]
    (when-not guard
      (fail! "Pinned identity implementation has no attachment guard" {}))
    (guard rt f)))

(defn persist-attachment!
  "Persist identity binding, provenance, and run evidence in one transaction.

  Callers validate the identity and run while holding `with-identity-guard`.
  A rejected batch leaves every strand and edge at its pre-transaction value."
  [rt {:keys [identity-strand run parent identity-attributes run-attributes]}]
  (let [self-parent? (= (:id identity-strand) (:id parent))
        result (batch/apply!
                rt
                {:refs (cond-> {:identity (:id identity-strand)
                                :run (:id run)}
                         (and parent (not self-parent?))
                         (assoc :parent (:id parent)))
                 :strands (cond-> []
                            (seq identity-attributes)
                            (conj {:ref :identity
                                   :attributes identity-attributes})
                            (seq run-attributes)
                            (conj {:ref :run
                                   :attributes run-attributes}))
                 :edges (cond-> [{:op :upsert
                                  :from :identity
                                  :to :run
                                  :type "performed"}]
                          (and parent (not self-parent?))
                          (conj {:op :upsert
                                 :from :parent
                                 :to :identity
                                 :type "parent-of"}))
                 :burn []})]
    {:identity-strand (weaver/show rt (get-in result [:refs :identity]))
     :run (weaver/show rt (get-in result [:refs :run]))}))

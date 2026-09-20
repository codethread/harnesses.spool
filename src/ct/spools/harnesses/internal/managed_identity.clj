(ns ct.spools.harnesses.internal.managed-identity
  "Atomic identity attachment and managed run evidence persistence."
  (:require [clojure.string :as str]
            [millhouse.spools.identity :as identity]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn with-identity-guard
  "Call `f` while holding the pinned Identity implementation's workspace guard."
  [rt f]
  (let [guard (ns-resolve 'millhouse.spools.identity 'with-identity-guard)]
    (when-not guard
      (fail! "Pinned identity implementation has no attachment guard" {}))
    (guard rt f)))

(defn- identity-record? [strand]
  (= "true" (attr-get strand :identity/session)))

(defn- matching-identities [rt attribute value]
  (filterv #(and (identity-record? %)
                 (= value (attr-get % attribute)))
           (weaver/list rt)))

(defn- unique-reservation [rt reservation-id]
  (let [matches (matching-identities rt :identity/reservation-id
                                     reservation-id)]
    (when-not (= 1 (count matches))
      (fail! "Identity reservation does not resolve uniquely"
             {:reservation-id reservation-id
              :matches (mapv :id matches)}))
    (first matches)))

(defn- unique-native-binding [rt harness native-session-id]
  (let [matches (filterv #(= harness (attr-get % :identity/harness))
                         (matching-identities rt :identity/native-session-id
                                              native-session-id))]
    (when (< 1 (count matches))
      (fail! "Native harness session has conflicting identity bindings"
             {:harness harness
              :native-session-id native-session-id
              :strand-ids (mapv :id matches)}))
    (first matches)))

(defn reservation-binding
  "Validate and return one reserved identity attachment binding.

  Callers must hold `with-identity-guard` from validation through persistence.
  The return includes the reserved identity strand and whether the identity
  already has this exact native binding."
  [rt {:keys [harness native-session-id reservation-id friendly-id]}]
  (let [reserved (unique-reservation rt reservation-id)
        supplied (identity/current rt friendly-id)
        native (unique-native-binding rt harness native-session-id)
        reserved-harness (attr-get reserved :identity/harness)
        reserved-session (attr-get reserved :identity/native-session-id)
        reservation-state (attr-get reserved :identity/reservation-state)]
    (when-not (= (:id supplied) (:id reserved))
      (fail! "Supplied identity does not match reservation"
             {:identity friendly-id
              :reservation-id reservation-id
              :reserved-identity (attr-get reserved :identity/id)}))
    (when-not (= harness reserved-harness)
      (fail! "Identity reservation belongs to another harness"
             {:reservation-id reservation-id
              :expected-harness reserved-harness
              :actual-harness harness}))
    (when-not (contains? #{"reserved" "attached"} reservation-state)
      (fail! "Identity reservation has invalid state"
             {:reservation-id reservation-id :state reservation-state}))
    (when-not (= (= "attached" reservation-state)
                 (and (string? reserved-session)
                      (not (str/blank? reserved-session))))
      (fail! "Identity reservation state conflicts with its native binding"
             {:reservation-id reservation-id
              :state reservation-state
              :native-session-id reserved-session}))
    (when (and reserved-session (not= native-session-id reserved-session))
      (fail! "Reserved identity is already attached to another native session"
             {:reservation-id reservation-id
              :identity (attr-get reserved :identity/id)
              :expected-native-session-id reserved-session
              :actual-native-session-id native-session-id}))
    (when (and native (not= (:id native) (:id reserved)))
      (fail! "Native harness session is already bound to another identity"
             {:harness harness
              :native-session-id native-session-id
              :identity (attr-get native :identity/id)
              :reserved-identity (attr-get reserved :identity/id)}))
    {:identity-strand reserved
     :already-attached? (= "attached" reservation-state)}))

(defn persist-provenance!
  "Persist the strict worker identity to run `performed` provenance edge."
  [rt identity-strand run]
  (batch/apply!
   rt
   {:refs {:identity (:id identity-strand)
           :run (:id run)}
    :strands []
    :edges [{:op :upsert
             :from :identity
             :to :run
             :type "performed"}]
    :burn []}))

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

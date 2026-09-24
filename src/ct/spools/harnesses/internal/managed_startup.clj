(ns ct.spools.harnesses.internal.managed-startup
  "Maintenance identity binding and native-provider launch classification."
  (:require [millhouse.spools.identity :as identity]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]))

(defn managed-harness?
  "Return whether concrete `harness` defers identity to native startup."
  [harness]
  (contains? #{"codex" "pi"} harness))

(defn commit-identity!
  "Bind maintenance identity; native providers defer identity to startup.

  Operation caller attribution is durable run evidence and never participates
  in strict worker identity binding."
  [rt {:keys [harness session-id run predecessor]}]
  (if (managed-harness? harness)
    {}
    (identity/bind!
     rt
     (cond-> {:harness harness
              :native-session-id session-id
              :run-id (:id run)}
       predecessor
       (assoc :expected-identity
              (attr-get predecessor :identity/id))))))

(defn retry-identity!
  "Return the retained binding for a native resume retry.

  Fresh native runs defer identity to startup. Maintenance providers return nil."
  [_rt run harness _session-id _effective]
  (when (and (managed-harness? harness) (attr-get run :harness/resumes))
    (when-not (= harness (attr-get run :harness/harness))
      (fail! "Native resume retry cannot change its managed provider"
             {:run-id (:id run)
              :retained (attr-get run :harness/harness)
              :requested harness}))
    (when-not (= "true" (attr-get run :harness/native-attached))
      (fail! "Native resume retry requires an attached identity"
             {:run-id (:id run)}))
    {:identity (attr-get run :identity/id)
     :prompt (attr-get run :identity/prompt)
     :native-attached true}))

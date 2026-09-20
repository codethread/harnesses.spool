(ns ct.spools.harnesses.internal.attribution
  "Durable actor evidence for harness run mutations."
  (:require [millstrand.api.notes.alpha :as notes]))

(defn record-action!
  "Append actor-attributed action evidence to `run-id` when an actor is supplied.

  The immutable note preserves each mutation actor independently of mutable run
  attributes. Identity reconciliation later projects the canonical
  `identity/by-identity` value into an `attributed` edge."
  [rt run-id action by-identity attributes]
  (when by-identity
    (notes/note!
     rt run-id (str "Agent run " action ".")
     (merge {:identity/by-identity by-identity
             :harness/action action}
            attributes))))

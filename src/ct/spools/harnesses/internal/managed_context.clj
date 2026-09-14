(ns ct.spools.harnesses.internal.managed-context
  "Construct managed startup responses after validated identity attachment."
  (:require [ct.spools.harnesses.internal.guidance :as guidance]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(defn response
  "Return the native guidance bundle or legacy managed context response."
  [rt run attached managed-context-schema]
  (if (guidance/native? run)
    (guidance/bundle rt run
                     (attr-get run :harness/session-id)
                     (:identity attached)
                     (:strand-id attached))
    {:schema managed-context-schema
     :run-id (:id run)
     :harness (attr-get run :harness/harness)
     :native-session-id (attr-get run :harness/session-id)
     :identity (:identity attached)
     :strand-id (:strand-id attached)
     :result (:result attached)
     :instruction (:instruction attached)
     :context {:schema managed-context-schema
               :identity-instruction (:instruction attached)
               :appended-system-prompts
               (or (attr-get run :harness/appended-system-prompts) [])}}))

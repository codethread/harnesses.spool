(ns ct.spools.harnesses.assignment.cli
  "CLI grammar and result projection for `strand agent assign`."
  (:require [ct.spools.harnesses.assignment :as assignment]
            [ct.spools.harnesses.internal.assignment :as internal]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(def assign-subcommand
  "Arg-spec fragment for `agent assign`."
  {:doc "Assign an agent to a work target without preclaiming it."
   :hook-class :mutating
   :deadline-class :standard
   :flags {:task {:type :string
                  :required? true
                  :doc "Target strand or kanban feature id."}
           :cwd {:type :string
                 :required? true
                 :doc "Explicit work directory. No worktree is created."}
           :policy {:type :string
                    :doc "Named assign policy. Defaults to stop-on-complete."}
           :after {:type :string
                   :doc "Settled predecessor for a fresh continuation on the same target."}
           :title {:type :string
                   :doc "Display title; defaults to Assign: <target title>."}
           :attributes {:type :string
                        :parse :json
                        :doc "Provider overlay JSON object."}
           :append-system-prompt
           {:type :string
            :doc "Append role or policy text to the system prompt."}
           :request-id {:type :string
                        :doc "Idempotency key. A repeat returns the same run."}
           :by-identity {:type :string
                         :doc "Friendly identity performing this operation."}}
   :positionals [{:name :agent
                  :type :string
                  :required? true
                  :doc "Available provider harness or alias."}]})

(defn assign-summary
  "Return the lean projection for an accepted assignment."
  [run]
  (cond-> {:id (:id run)
           :title (:title run)
           :state (:state run)
           :alias (attr-get run :harness/alias)
           :harness (attr-get run :harness/harness)
           :mode (attr-get run :harness/mode)
           :status (life/status run)
           :substatus (life/substatus run)
           :settled (life/settled? run)
           :session-id (attr-get run :harness/session-id)
           :target (attr-get run :harness/target)
           :policy (internal/context-get (attr-get run :harness/context)
                                         "assignment/policy")
           :cwd (attr-get run :harness/cwd)
           :identity (attr-get run :identity/id)
           :request-id (attr-get run :harness/request-id)}
    (attr-get run :harness/logical-id)
    (assoc :logical-id (attr-get run :harness/logical-id))))

(defn op-assign
  "Accept an assignment from parsed CLI args and return its summary."
  [runtime args]
  (assign-summary
   (assignment/assign!
    runtime
    (cond-> {:harness (:agent args)
             :target (:task args)
             :cwd (:cwd args)}
      (some? (:policy args)) (assoc :policy (:policy args))
      (some? (:after args)) (assoc :after (:after args))
      (some? (:title args)) (assoc :title (:title args))
      (some? (:attributes args)) (assoc :attributes (:attributes args))
      (some? (:append-system-prompt args))
      (assoc :append-system-prompt (:append-system-prompt args))
      (some? (:request-id args)) (assoc :request-id (:request-id args))
      (some? (:by-identity args)) (assoc :by-identity (:by-identity args))))))

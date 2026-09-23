(ns ct.spools.harnesses.internal.assignment
  "Private assignment helpers: target checks, guidance, and run accept."
  (:require [clojure.string :as str]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.internal.publication :as publication]
            [millhouse.spools.kanban :as kanban]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn require-cwd
  "Return `cwd` when it is a non-blank path."
  [cwd]
  (when-not (and (string? cwd) (not (str/blank? cwd)))
    (fail! "Assignment requires an explicit cwd" {:cwd cwd}))
  cwd)

(defn- parent-cards
  [rt id]
  (->> (graph/incoming-edges rt [id] "parent-of")
       (map :from_strand_id)
       (map #(weaver/show rt %))
       (filter #(= "true" (attr-get % :kanban/card)))
       (sort-by :id)
       vec))

(defn root-targets
  "Return every ancestor work card for `target`, nearest first.

  The accepted assignment freezes this scope. A target with multiple work-card
  parents or a cycle is malformed and fails rather than selecting one path."
  [rt target]
  (loop [id (:id target)
         seen #{(:id target)}
         ancestors []]
    (let [parents (parent-cards rt id)]
      (when (< 1 (count parents))
        (fail! "Assignment target has ambiguous work-card parents"
               {:target (:id target)
                :strand id
                :parents (mapv :id parents)}))
      (if-let [parent (first parents)]
        (do
          (when (contains? seen (:id parent))
            (fail! "Assignment target has a work-card parent cycle"
                   {:target (:id target)
                    :strand (:id parent)}))
          (recur (:id parent)
                 (conj seen (:id parent))
                 (conj ancestors parent)))
        ancestors))))

(defn require-target
  "Return the target strand, failing when it is missing, closed, or foreign."
  [rt target-id]
  (when-not (and (string? target-id) (not (str/blank? target-id)))
    (fail! "Assignment target is invalid" {:target target-id}))
  (let [target (weaver/show rt target-id)]
    (when-not target
      (fail! "Assignment target does not exist" {:target target-id}))
    (when (= "closed" (:state target))
      (fail! "Assignment target is closed" {:target target-id}))
    (when (= "true" (attr-get target :harness/run))
      (fail! "Assignment target is foreign"
             {:target target-id :reason "harness-run"}))
    (when (= "true" (attr-get target :identity/session))
      (fail! "Assignment target is foreign"
             {:target target-id :reason "identity-session"}))
    (when (= "epic" (attr-get target :kanban/type))
      (fail! "Assignment target is invalid"
             {:target target-id :reason "kanban-epic"}))
    target))

(defn require-assigned-run
  "Return harness run `id`, failing when it is absent or not a run."
  [rt id]
  (let [run (weaver/show rt id)]
    (when-not run
      (fail! "Harness run not found" {:id id}))
    (when-not (= "true" (attr-get run :harness/run))
      (fail! "Strand is not a harness run" {:id id}))
    run))

(defn target-profile
  "Return the Kanban target kind and latest explicit ownership projection."
  [rt target]
  (cond
    (= "true" (attr-get target :kanban/task))
    (let [direct (kanban/current-ownership rt (:id target))
          projected (kanban/task-ownership rt target)]
      {:kind "task"
       :owner (:owner (:claim projected))
       :ownership-source (:source projected)
       :direct-owner (:owner direct)
       :feature (:feature projected)})

    (= "feature" (attr-get target :kanban/type))
    {:kind "feature"
     :lane (attr-get target :kanban/lane)
     :owner (:owner (kanban/current-ownership rt (:id target)))}

    :else
    {:kind "strand"}))

(defn freeze-context
  "Return the durable JSON-ish assignment context."
  [target cwd policy profile]
  {"assignment/policy" (:name policy)
   "assignment/policy-text" (:text policy)
   "assignment/target" (:id target)
   "assignment/target-kind" (:kind profile)
   "assignment/cwd" cwd})

(defn- target-read-command
  [target profile]
  (if (= "feature" (:kind profile))
    (str "strand kanban card " (:id target))
    (str "strand show " (:id target))))

(defn- stable-ownership-guidance
  [profile]
  (case (:kind profile)
    "feature"
    (format-alpha/prose
     "
       Before changing the feature, inspect its current ownership. Only an
       unowned pending feature may follow a first-claim instruction from the
       current run prompt. If you are already the latest explicit owner,
       continue without claiming again. If another owner is current, require an
       explicit handoff/reclaim before editing. Assignment never changes owner.
       "
     {})

    "task"
    (format-alpha/prose
     "
       This run serves the task directly, not its parent feature. Do not claim
       or re-claim the parent feature, and do not issue `kanban claim` against
       the task from this generated guidance. Inspect the task's direct or
       inherited ownership and use its supported coordination path. If that
       ownership does not authorize your work, stop and report the required
       handoff instead of changing ownership silently.
       "
     {})

    (format-alpha/prose
     "
       This target is not a Kanban feature or task. Assignment does not create
       an ownership claim. Follow the target's own coordination contract.
       "
     {})))

(defn- current-feature-guidance
  [target profile identity cwd run-id]
  (cond
    (and (nil? (:owner profile)) (= "pending" (:lane profile)))
    (format-alpha/prose
     "
       This is an unowned pending feature. Record its first claim before work:

       ```text
       strand kanban claim {id} --owner {identity} --branch <your-branch> --worktree {cwd} --run-id {run-id}
       ```
       "
     {:id (:id target)
      :identity identity
      :cwd cwd
      :run-id run-id})

    (= identity (:owner profile))
    (format-alpha/prose
     "
       You are already the feature's latest explicit owner ({identity}).
       Continue without running `kanban claim` again; a changed run id or other
       context would make a same-owner claim an invalid retry.
       "
     {:identity identity})

    (:owner profile)
    (format-alpha/prose
     "
       The feature's latest explicit owner is {owner}, not {identity}.
       Assignment does not transfer ownership. Obtain authorization, then use
       the current `strand kanban claim` handoff/reclaim contract to record
       {identity} as the new owner before editing. Do not toggle lanes or
       overwrite owner attributes.
       "
     {:owner (:owner profile)
      :identity identity})

    :else
    (format-alpha/prose
     "
       The feature has no explicit owner, but its lane is {lane}, not pending.
       This is not a valid first-claim state. Stop and report the ownership/lane
       inconsistency instead of changing the lane or inventing an owner.
       "
     {:lane (or (:lane profile) "missing")})))

(defn- current-task-guidance
  [profile identity]
  (cond
    (= identity (:direct-owner profile))
    (format-alpha/prose
     "
       You are already this task's direct owner ({identity}). Continue the task
       without changing its ownership or the parent feature's ownership.
       "
     {:identity identity})

    (:direct-owner profile)
    (format-alpha/prose
     "
       This task is directly owned by {owner}, not {identity}. Obtain an
       explicit task handoff through the coordinator-supported path before
       editing. Do not claim the parent feature or silently replace ownership.
       "
     {:owner (:direct-owner profile)
      :identity identity})

    (= "inherited" (:ownership-source profile))
    (format-alpha/prose
     "
       This task has no direct owner and currently projects inherited feature
       owner {owner}. Work only on this task. Do not re-claim the feature; if
       direct task ownership is required, request it through the supported task
       coordination path.
       "
     {:owner (:owner profile)})

    :else
    (format-alpha/prose
     "
       This task has no direct or inherited owner. The assignment still serves
       only this task and does not claim it or its parent feature. Report any
       required ownership transition before editing.
       "
     {})))

(defn- current-ownership-guidance
  [target profile identity cwd run-id]
  (case (:kind profile)
    "feature" (current-feature-guidance target profile identity cwd run-id)
    "task" (current-task-guidance profile identity)
    (stable-ownership-guidance profile)))

(defn build-guidance
  "Return the work prompt for one assignment.

  Current ownership guidance is emitted only after the run has a worker
  identity and run id. Frozen provider guidance contains state-independent
  rules so native continuation cannot replay a stale first-claim assertion."
  [{:keys [target cwd policy profile identity run-id current-state?]}]
  (let [body (attr-get target :body)
        body-block (if (and (string? body) (not (str/blank? body)))
                     (str body "\n\n")
                     "")
        ownership-guidance (if current-state?
                             (current-ownership-guidance
                              target profile identity cwd run-id)
                             (stable-ownership-guidance profile))]
    (format-alpha/prose
     "
       You are assigned to work on {title} ({id}).

       {body-block}Read the work target:

       ```text
       {read-command}
       ```

       Your authoritative identity is {identity-line}.
       Working directory (explicit; do not create a worktree): {cwd}
       This run: {run-line}

       {ownership-guidance}

       Work to completion or report a blocker on the work target.

       Policy ({policy-name}):
       {policy-text}
       "
     {:title (:title target)
      :id (:id target)
      :body-block body-block
      :read-command (target-read-command target profile)
      :identity-line (or identity
                         "the identity in this session's canonical identity instruction")
      :cwd cwd
      :run-line (or run-id "this harness run (the strand that serves the target)")
      :ownership-guidance ownership-guidance
      :policy-name (:name policy)
      :policy-text (:text policy)})))

(defn build-system-guidance
  "Return state-independent assignment guidance for provider replay."
  [{:keys [target cwd policy profile]}]
  (build-guidance {:target target
                   :cwd cwd
                   :policy policy
                   :profile profile
                   :identity "the identity in this session's canonical identity instruction"
                   :run-id "{{RUN_ID}}"
                   :current-state? false}))

(defn context-get
  "Return one assignment context value, accepting Weaver key variants."
  [context k]
  (when (map? context)
    (or (get context k)
        (get context (keyword k))
        (get context (name k))
        (get context (keyword (str/replace (name k) "/" "."))))))

(defn- create-request
  [{:keys [harness cwd guidance system-guidance title attributes append-system-prompt
           by-identity target root-targets context request-id logical-id after]}]
  (cond-> {:harness harness
           :prompt guidance
           :cwd cwd
           :title title}
    (some? attributes) (assoc :attributes attributes)
    (some? system-guidance) (assoc :append-system-prompt system-guidance)
    (some? append-system-prompt) (update :append-system-prompt
                                         #(if (str/blank? %)
                                            append-system-prompt
                                            (str % "\n\n" append-system-prompt)))
    (some? by-identity) (assoc :by-identity by-identity)
    target (assoc :target target)
    (seq root-targets) (assoc :root-targets root-targets)
    context (assoc :context context)
    request-id (assoc :request-id request-id)
    logical-id (assoc :logical-id logical-id)
    after (assoc :after after)))

(declare enrich-guidance!)

(defn accept-run!
  "Create or reuse the harness run for one prepared assignment.

  Core publication owns request idempotency and target exclusivity. The
  assignment bridge supplies the target, frozen context, and request key in
  that one authoritative create request; it never stamps those bindings after
  publication."
  [rt prepared]
  (binding [publication/*enrich*
            (fn [runtime run]
              (enrich-guidance!
               runtime run (:context prepared) (:policy prepared)
               (:cwd prepared) (weaver/show runtime (:target prepared))))]
    (harnesses/create! rt (create-request prepared))))

(defn enrich-guidance!
  "Stamp run identity and current ownership guidance into the accepted run."
  [rt run frozen policy cwd target]
  (let [run-id (:id run)
        root-targets (attr-get run :harness/root-targets)
        identity (attr-get run :identity/id)
        profile (target-profile rt (weaver/show rt (:id target)))
        context (cond-> (assoc frozen "assignment/run-id" run-id)
                  identity
                  (assoc "assignment/identity" identity)
                  (:lane profile)
                  (assoc "assignment/target-lane" (:lane profile))
                  (:owner profile)
                  (assoc "assignment/owner-at-acceptance" (:owner profile))
                  (:ownership-source profile)
                  (assoc "assignment/ownership-source"
                         (:ownership-source profile)))
        guidance (build-guidance {:target target
                                  :cwd cwd
                                  :policy policy
                                  :profile profile
                                  :identity identity
                                  :run-id run-id
                                  :current-state? (some? identity)})]
    (when (seq root-targets)
      (weaver/update!
       rt (:id target)
       {:edges (mapv #(hash-map :type "serves-root" :to %)
                     root-targets)}))
    (weaver/update!
     rt run-id
     {:attributes {:harness/prompt guidance
                   :harness/context context}})))

(defn inherit-from-predecessor
  "Return `request` with frozen policy taken from a settled predecessor."
  [rt request]
  (let [predecessor (require-assigned-run rt (:after request))
        pred-target (attr-get predecessor :harness/target)
        pred-context (attr-get predecessor :harness/context)
        frozen-name (context-get pred-context "assignment/policy")
        frozen-text (context-get pred-context "assignment/policy-text")]
    (when-not (contains? #{"stopped" "failed"}
                         (attr-get predecessor :harness/status))
      (fail! "Fresh continuation requires a settled predecessor"
             {:after (:after request)
              :status (attr-get predecessor :harness/status)}))
    (when-not (= "true" (attr-get predecessor :harness/settled))
      (fail! "Fresh continuation requires a settled predecessor"
             {:after (:after request)
              :settled (attr-get predecessor :harness/settled)}))
    (when (and pred-target (not= pred-target (:target request)))
      (fail! "Fresh continuation must keep the predecessor target"
             {:after (:after request)
              :target (:target request)
              :predecessor-target pred-target}))
    (when-not frozen-text
      (fail! "Predecessor has no frozen assignment guidance"
             {:after (:after request)}))
    (assoc request
           :frozen-name frozen-name
           :frozen-text frozen-text
           :logical-id (attr-get predecessor :harness/logical-id)
           :root-targets (attr-get predecessor :harness/root-targets))))

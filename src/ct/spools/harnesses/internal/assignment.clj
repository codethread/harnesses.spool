(ns ct.spools.harnesses.internal.assignment
  "Private assignment helpers: target checks, guidance, and run accept."
  (:require [clojure.string :as str]
            [ct.spools.harnesses :as harnesses]
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

(defn freeze-context
  "Return the durable JSON-ish assignment context."
  [target cwd policy]
  {"assignment/policy" (:name policy)
   "assignment/policy-text" (:text policy)
   "assignment/target" (:id target)
   "assignment/cwd" cwd})

(defn build-guidance
  "Return the work prompt for one assignment."
  [{:keys [target cwd policy identity run-id]}]
  (let [body (attr-get target :body)
        body-block (if (and (string? body) (not (str/blank? body)))
                     (str body "\n\n")
                     "")]
    (str
     (format-alpha/prose
      "
        You are assigned to work on {title} ({id}).

        {body-block}Read the work target:

        ```text
        strand kanban card {id}
        ```

        Your authoritative identity is {identity-line}.
        Working directory (explicit; do not create a worktree): {cwd}
        This run: {run-line}

        Claim the card yourself when you start. Do not assume it is claimed:

        ```text
        strand kanban claim {id} --owner {owner-token} --branch <your-branch> --worktree {cwd} --run-id {run-token}
        ```

        Work to completion or report a blocker on the card.

        Policy ({policy-name}):
        {policy-text}
        "
      {:title (:title target)
       :id (:id target)
       :body-block body-block
       :identity-line (or identity
                          "$MILLSTRAND_AGENT_ID (exported to this process)")
       :cwd cwd
       :run-line (or run-id "this harness run (the strand that serves the card)")
       :owner-token (or identity "$MILLSTRAND_AGENT_ID")
       :run-token (or run-id "<this-run-id>")
       :policy-name (:name policy)
       :policy-text (:text policy)}))))

(defn build-system-guidance
  "Return frozen assignment guidance for provider system-prompt replay."
  [{:keys [target cwd policy]}]
  (build-guidance {:target target
                   :cwd cwd
                   :policy policy
                   :identity "{{AGENT_ID}}"
                   :run-id "{{RUN_ID}}"}))

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

(defn- already-assigned?
  [run]
  (boolean (context-get (attr-get run :harness/context) "assignment/run-id")))

(defn accept-run!
  "Create or reuse the harness run for one prepared assignment.

  Core publication owns request idempotency and target exclusivity. The
  assignment bridge supplies the target, frozen context, and request key in
  that one authoritative create request; it never stamps those bindings after
  publication."
  [rt prepared]
  (let [run (harnesses/create! rt (create-request prepared))]
    {:run run :existing? (already-assigned? run)}))

(defn enrich-guidance!
  "Stamp run identity and freeze its ancestor scope into the graph."
  [rt run frozen policy cwd target]
  (let [run-id (:id run)
        root-targets (attr-get run :harness/root-targets)
        identity (attr-get run :identity/id)
        context (assoc frozen
                       "assignment/run-id" run-id
                       "assignment/identity" (or identity ""))
        guidance (build-guidance {:target target
                                  :cwd cwd
                                  :policy policy
                                  :identity identity
                                  :run-id run-id})]
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

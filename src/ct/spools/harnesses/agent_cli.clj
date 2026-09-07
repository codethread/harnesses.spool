(ns ct.spools.harnesses.agent-cli
  "CLI operation for tracked coding-agent runs."
  (:refer-clojure :exclude [agent])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.assignment.cli :as assignment-cli]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.cli :as cli]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [millhouse.spools.identity :as identity]
            [millstrand.api.format.alpha :as fmt]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(declare ^:private op-run
         ^:private op-assign
         ^:private agent-list
         ^:private identity-alias
         ^:private op-retry
         ^:private op-resume
         ^:private op-show
         ^:private op-runs
         ^:private resumable-runs
         ^:private summary)

(s/def ::op-context
  (s/and map?
         #(s/valid? ::harness/runtime (:op/runtime %))
         #(map? (:op/args %))))
(s/def ::alias string?)
(s/def ::harness string?)
(s/def ::mode #{"headless" "interactive"})
(s/def ::status life/statuses)
(s/def ::substatus (s/nilable life/substatuses))
(s/def ::session-id string?)
(s/def ::launcher string?)
(s/def ::exit-code int?)
(s/def ::result string?)
(s/def ::error string?)
(s/def ::resumes string?)
(s/def ::identity string?)
(s/def ::updated-at string?)
(s/def ::settled boolean?)
(s/def ::settlement string?)
(s/def ::settlement-gap string?)
(s/def ::resumable boolean?)
(s/def ::resume-reason string?)
(s/def ::stop-reason string?)
(s/def ::logical-id string?)
(s/def ::target string?)
(s/def ::request-id string?)
(s/def ::attempt int?)
(s/def ::run-summary
  (s/keys :req-un [::harness/id ::harness/title ::harness/state
                   ::alias ::harness ::mode ::status ::substatus ::session-id
                   ::settled]
          :opt-un [::launcher ::exit-code ::result ::error ::resumes
                   ::identity ::updated-at ::settlement ::settlement-gap
                   ::resumable ::resume-reason ::stop-reason ::logical-id
                   ::target ::request-id ::attempt]))
(s/def ::runs (s/coll-of ::run-summary :kind vector?))
(s/def ::config-result map?)
(s/def ::resolution string?)
(s/def ::provider string?)
(s/def ::model string?)
(s/def ::thinking string?)
(s/def ::description string?)
(s/def ::modes (s/coll-of ::harness/mode-name :kind vector? :min-count 1))
(s/def ::agent-list-entry
  (s/keys :req-un [::harness/name ::harness/kind ::resolution ::provider]
          :opt-un [::model ::thinking ::description ::modes]))
(s/def ::agent-list (s/coll-of ::agent-list-entry :kind vector?))
(s/def ::op-result
  (s/or :run ::run-summary
        :runs ::runs
        :registry ::harness/registry-list
        :agent-list ::agent-list
        :config ::config-result))

(def ^:private agent-prime
  "Terse runbook projected by `strand prime agent`."
  (fmt/prose
   "
     `agent` runs tracked coding agents. The normal path is headless: select an
     available provider harness or alias, run it with a prompt, then wait on its
     run id.

     List currently usable agents with their resolution, model, thinking level,
     and guidance:

     ```text
     strand agent list
     ```

     Assign a provider to an explicit work target:

     ```sh
     strand agent assign <agent> --task <feature-id> --cwd <workdir> --policy <name>
     ```

     The agent claims the target itself. Assignment does not create a worktree
     or close the target when the process exits.

     Run an ad-hoc agent and collect its work:

     ```sh
     strand agent run <agent> --prompt <prompt>
     strand await --query agent-run-terminal --param run-id=<run-id> --min-count 1
     strand agent show <run-id>
     ```

     Waiting is `strand await` on a named query, not an agent verb. Each query
     selects the run only once its condition actually holds, so a run id that
     does not exist can never satisfy a wait. Use `agent-run-terminal` for
     work being finished, and `agent-run-settled` when you additionally need
     proof the provider process is gone, which is what a native resume requires.

     Inspect with `agent show <run-id>` (or `--task`/`--request`) and
     `agent runs --active`. Neither dumps logs.

     Stop one run by exact id with `agent stop <run-id> --reason <why>`. The
     request is durable and idempotent, and the run stays `running` until
     settlement is observed. Stopping a run does not close the work it serves.

     A failed run stays active. Correct its agent selection, cwd, provider
     attributes, or runtime flags and retry it in place. Resume a settled run
     when the same provider session should continue with a new prompt.
     "
   {}))

(def ^:private agent-about
  "Detailed orientation projected by `strand about agent`."
  (fmt/prose
   "
     `agent` manages provider-neutral, tracked coding-agent runs. It resolves
     concrete provider harnesses and workspace-defined aliases into launch
     settings, records each run as a strand, and exposes one lifecycle across
     providers. Run `strand help agent <verb>` for a verb's exact arguments and
     flags.

     Find agents available to run with:

     ```text
     strand agent list
     ```

     The default list contains only currently available entries. Each concise
     record identifies a concrete provider harness or an alias, its selected
     resolution path, effective provider, model and thinking level, and its
     description or supported modes. Use `strand agent list --full` for the
     complete visible registry, including unavailable candidates and their
     reasons.

     Identity-bearing commands accept `--by-identity`. On `list`, it applies the
     caller alias's `:allow` or `:deny` visibility policy. On `run` and `resume`,
     it records the caller as the parent of the spawned session identity.

     Workspace startup code registers aliases. An alias can layer a model,
     effort, and provider attributes over a concrete provider harness, or try
     ordered candidates selected by runtime flags. The agent passed to `run` may
     be either an available harness or an available alias.

     The configured effort usually works. Override it with `--effort`, or its
     `--thinking` synonym, when the user asks or when judgment warrants more
     reasoning. Values are model-specific, though `high` and `xhigh` are commonly
     available. `--attributes` applies a provider overlay for that run.

     Runtime flags are process-local and affect agent availability immediately:

     ```text
     strand agent config list
     strand agent config set harness/claude false
     strand agent config set seat/example true
     strand agent config unset seat/example
     ```

     Every concrete provider harness has a `harness/<name>` flag and is enabled
     unless that flag is explicitly false. Alias conditions may refer to
     additional workspace-defined flags; an unset condition flag is false.
     `unset` removes an override rather than assigning false. These settings are
     not persistent configuration, so durable defaults and aliases belong in
     workspace startup code. List agents again after changing flags to see the
     resulting alias selection and availability.

     Runs are headless by default, require a prompt, and execute asynchronously.
     A run carries a `status` of `ready`, `running`, `stopped`, or `failed`, and
     a `substatus` saying why: `pending` for a ready run, `completed` or
     `requested` once stopped, and `launch`, `execution`, or `reconciliation`
     once failed. A running run has no substatus unless a stop is in flight.
     `settled` is separate and stronger: it means a terminal
     process fact was observed, so the provider session is provably free. A
     failed run is not automatically settled.

     `--target` binds a run to the strand it serves, `--context` carries durable
     caller data, and `--request-id` makes creation idempotent: repeating a
     request returns the same run, and reusing the key for different work fails
     and names the run already holding it. `assign` requires an explicit cwd,
     freezes the registered policy name and exact prose, and queues blocked
     targets until their dependencies close.

     `retry` reuses a failed ad hoc run after correction, and refuses runs bound
     to a request id or target, which should be continued or requested afresh
     instead. `resume` creates a *new* run continuing a settled provider
     session, reusing the predecessor's exact provider, session, and initial
     guidance rather than resolving its alias again. An ineligible predecessor
     fails loudly; it never falls back to a silent fresh run.

     Set `--interactive` on `run` or `resume` only when the user asks to work in
     the provider session. It launches the provider in the caller's terminal;
     `resumable` lists completed interactive runs available to continue.
     "
   {}))

(millstrand/defop agent
  "Create and manage tracked coding-agent runs.

  Run, retry, and resume may schedule asynchronous headless work. Every
  subcommand returns after its immediate transition; nothing here blocks. Wait
  with `strand await` on the `agent-run-*` named queries."
  {:arg-spec cli/agent-arg-spec
   :about agent-about
   :prime agent-prime}
  [{:op/keys [runtime args cwd] :as ctx}]
  (require-valid! ::op-context ctx "agent op received an invalid operation context")
  (when-let [friendly-id (:by-identity args)]
    (identity/current runtime friendly-id))
  (require-valid!
   ::op-result
   (case (:subcommand args)
     ["assign"] (op-assign runtime args)
     ["run"] (op-run runtime args cwd)
     ["show"] (op-show runtime args)
     ["runs"] (op-runs runtime args)
     ["stop"] (summary (execution/stop! runtime (:run-id args)
                                        (select-keys args [:reason])))
     ["retry"] (op-retry runtime args)
     ["resumable"] (resumable-runs runtime)
     ["resume"] (op-resume runtime args)
     ["self-complete"] (summary (harness/self-complete! runtime
                                                        (:run-id args)
                                                        (:result args)))
     ["_started"] (summary (execution/mark-interactive-running! runtime
                                                                (:run-id args)))
     ["_finished"] (summary (execution/finish-interactive! runtime
                                                           (:run-id args)
                                                           (:exit-code args)))
     ["list"] (let [requesting-alias
                    (when-let [friendly-id (:by-identity args)]
                      (identity-alias runtime friendly-id))
                    registry (if requesting-alias
                               (harness/harnesses runtime requesting-alias)
                               (harness/harnesses runtime))]
                (if (:full args)
                  registry
                  (agent-list runtime registry)))
     ["config" "list"] {:flags (harness/flags runtime)}
     ["config" "set"] {:flag (:flag args)
                       :value (harness/set-flag! runtime (:flag args)
                                                 (:value args))}
     ["config" "unset"] {:flag (:flag args)
                         :removed (harness/unset-flag! runtime (:flag args))})
   "agent op produced an invalid result"))

(defn- resolution-path
  [entries entry]
  (loop [current entry
         path []
         seen #{}]
    (let [current-name (:name current)]
      (when (contains? seen current-name)
        (fail! "Agent list found a cycle in an available resolution"
               {:name (:name entry) :path path :cycle current-name}))
      (if (= "harness" (:kind current))
        (conj path current-name)
        (let [parent-name
              (or (:selected-parent current)
                  (fail! "Available alias has no selected parent"
                         {:name current-name}))
              parent
              (or (get entries parent-name)
                  (fail! "Available alias selected an unregistered parent"
                         {:name current-name :parent parent-name}))]
          (recur parent
                 (conj path current-name)
                 (conj seen current-name)))))))

(defn- selected-candidate
  [entry]
  (when (= "alias" (:kind entry))
    (let [index
          (or (:selected-candidate entry)
              (fail! "Available alias has no selected candidate"
                     {:name (:name entry)}))]
      (or (get (:candidates entry) index)
          (fail! "Available alias selected a missing candidate"
                 {:name (:name entry) :candidate index})))))

(defn- concise-agent-entry
  [rt entries entry]
  (let [{:keys [harness generated]} (harness/resolve-harness rt (:name entry))
        description (:doc (selected-candidate entry))]
    (cond-> {:name (:name entry)
             :kind (:kind entry)
             :resolution (str/join " -> " (resolution-path entries entry))
             :provider harness}
      description (assoc :description description)
      (:harness/model generated) (assoc :model (:harness/model generated))
      (:harness/effort generated) (assoc :thinking (:harness/effort generated))
      (= "harness" (:kind entry)) (assoc :modes (:modes entry)))))

(defn- agent-list
  [rt registry]
  (let [entries (into {} (map (juxt :name identity)) (harness/harnesses rt))]
    (->> registry
         (filter :available)
         (mapv #(concise-agent-entry rt entries %)))))

(defn- identity-alias [rt friendly-id]
  (let [identity (identity/current rt friendly-id)
        run-ids (mapv :to_strand_id
                      (graph/outgoing-edges rt [(:id identity)] "performed"))
        latest-run (->> run-ids
                        (map #(weaver/show rt %))
                        (sort-by (juxt :updated_at :id) #(compare %2 %1))
                        first)]
    (or (some-> latest-run (attr-get :harness/alias))
        (fail! "Identity has no associated harness run"
               {:identity friendly-id}))))

(defn- full-run [rt id]
  (or (weaver/show rt id) (fail! "Agent run not found" {:id id})))

(defn- overlay-map [value]
  (cond
    (nil? value) {}
    (map? value) value
    :else (fail! "--attributes must be a JSON object" {:attributes value})))

(defn- overlay-context [value]
  (cond
    (nil? value) nil
    (map? value) value
    :else (fail! "--context must be a JSON object" {:context value})))

(defn- summary
  "Project one run into the compact record every agent verb returns.

  It carries state, settlement evidence, and identifiers, and never the run's
  output logs: `--result` text is included because it is the run's answer, but
  stdout and stderr stay in custody where they belong."
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
           :session-id (attr-get run :harness/session-id)}
    (attr-get run :harness/settlement)
    (assoc :settlement (attr-get run :harness/settlement))
    (attr-get run :harness/settlement-gap)
    (assoc :settlement-gap (attr-get run :harness/settlement-gap))
    (attr-get run :harness/stop-reason)
    (assoc :stop-reason (attr-get run :harness/stop-reason))
    (attr-get run :harness/logical-id)
    (assoc :logical-id (attr-get run :harness/logical-id))
    (attr-get run :harness/target) (assoc :target (attr-get run :harness/target))
    (attr-get run :harness/request-id)
    (assoc :request-id (attr-get run :harness/request-id))
    (attr-get run :harness/attempt) (assoc :attempt (attr-get run :harness/attempt))
    (some? (attr-get run :harness/exit-code))
    (assoc :exit-code (attr-get run :harness/exit-code))
    (attr-get run :harness/result) (assoc :result (attr-get run :harness/result))
    (attr-get run :harness/error) (assoc :error (attr-get run :harness/error))
    (attr-get run :harness/resumes) (assoc :resumes (attr-get run :harness/resumes))
    (attr-get run :identity/id) (assoc :identity (attr-get run :identity/id))
    (:updated_at run) (assoc :updated-at (:updated_at run))))

(defn- detailed
  "Return `summary` plus the run's resume eligibility and its reason."
  [rt run]
  (let [{:keys [eligible? reason]} (harness/resume-eligibility rt (:id run))]
    (assoc (summary run) :resumable eligible? :resume-reason reason)))

(defn- op-show
  "Show exactly one run, selected by id, served target, or request id."
  [rt {:keys [run-id task request]}]
  (let [selectors (remove nil? [run-id task request])]
    (when-not (= 1 (count selectors))
      (fail! "agent show requires exactly one of a run id, --task, or --request"
             {:run-id run-id :task task :request request}))
    (detailed
     rt
     (cond
       run-id (full-run rt run-id)

       :else
       (let [clause (if task
                      [:edge/out "serves" [:= :id task]]
                      [:= [:attr "harness/request-id"] request])
             matches (weaver/list rt
                                  [:and [:= [:attr "harness/run"] "true"] clause]
                                  {})]
         (case (count matches)
           0 (fail! "No agent run matches the selector"
                    {:task task :request request})
           1 (first matches)
           ;; Several runs may have served one target over time; the caller
           ;; asked for a run, so name them rather than picking one silently.
           (fail! "Selector matches multiple agent runs"
                  {:task task :request request :runs (mapv :id matches)})))))))

(defn- op-runs
  "List runs compactly, optionally narrowed to active ones or to one target."
  [rt {:keys [active task]}]
  (let [clauses (cond-> [[:= [:attr "harness/run"] "true"]]
                  task (conj [:edge/out "serves" [:= :id task]]))
        runs (weaver/list rt (into [:and] clauses) {})]
    (->> runs
         (filter #(if active (life/active? %) true))
         (sort-by (juxt :created_at :id) #(compare %2 %1))
         (mapv summary))))

(defn- resumable-runs
  "List settled interactive lineage heads that can still be continued."
  [rt]
  (let [runs (weaver/list rt
                          [:and
                           [:= [:attr "harness/run"] "true"]
                           [:= [:attr "harness/mode"] "interactive"]
                           [:= [:attr "harness/settled"] "true"]]
                          {})
        continued (into #{} (keep #(attr-get % :harness/resumes)) runs)]
    (->> runs
         (remove #(contains? continued (:id %)))
         (map #(detailed rt %))
         (filter :resumable)
         (sort-by :updated-at #(compare %2 %1))
         vec)))

(defn- interactive-plan [rt run]
  (assoc (summary run) :launcher (execution/prepare-interactive! rt run)))

(defn- op-assign
  [rt args]
  (let [accepted (assignment-cli/op-assign rt args)]
    (execution/schedule! rt)
    accepted))

(defn- op-run
  [rt {:keys [agent interactive prompt append-system-prompt cwd attributes title
              by-identity target context request-id]
       :as args}
   op-cwd]
  (let [effort (if (contains? args :effort) (:effort args) (:thinking args))
        attributes (cond-> (overlay-map attributes)
                     (some? effort) (assoc :harness/effort effort))
        run (harness/create!
             rt
             (cond-> {:harness agent
                      :mode (if interactive :interactive :headless)
                      :cwd (or cwd op-cwd)
                      :attributes attributes}
               (some? prompt) (assoc :prompt prompt)
               (some? append-system-prompt)
               (assoc :append-system-prompt append-system-prompt)
               (some? title) (assoc :title title)
               (some? by-identity) (assoc :by-identity by-identity)
               (some? target) (assoc :target target)
               (some? context) (assoc :context (overlay-context context))
               (some? request-id) (assoc :request-id request-id)))]
    (if interactive
      (interactive-plan rt run)
      (do
        (execution/schedule! rt)
        (summary run)))))

(defn- op-retry [rt args]
  (summary
   (harness/retry!
    rt (:run-id args)
    (cond-> {}
      (contains? args :agent) (assoc :harness (:agent args))
      (contains? args :cwd) (assoc :cwd (:cwd args))
      (contains? args :attributes)
      (assoc :attributes (overlay-map (:attributes args)))))))

(defn- op-resume [rt args]
  (let [predecessor (harness/resolve-resume-run
                     rt (select-keys args [:run-id :session-id :identity
                                           :logical-id]))
        run (harness/resume!
             rt (:id predecessor)
             (cond-> {:mode (if (:interactive args) :interactive :headless)}
               (contains? args :prompt) (assoc :prompt (:prompt args))
               (contains? args :title) (assoc :title (:title args))
               (contains? args :by-identity)
               (assoc :by-identity (:by-identity args))
               (contains? args :request-id)
               (assoc :request-id (:request-id args))))]
    (if (:interactive args)
      (interactive-plan rt run)
      (do
        (execution/schedule! rt)
        (summary run)))))

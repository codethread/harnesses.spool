(ns ct.spools.harnesses.agent-cli
  "CLI operation for tracked coding-agent runs."
  (:refer-clojure :exclude [agent])
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.cli :as cli]
            [millstrand.api.format.alpha :as fmt]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(declare ^:private op-run
         ^:private await!
         ^:private agent-list
         ^:private op-retry
         ^:private op-resume
         ^:private resumable-runs
         ^:private summary)

(s/def ::op-context
  (s/and map?
         #(s/valid? ::harness/runtime (:op/runtime %))
         #(map? (:op/args %))))
(s/def ::alias string?)
(s/def ::harness string?)
(s/def ::mode #{"headless" "interactive"})
(s/def ::phase #{"pending" "running" "done" "failed"})
(s/def ::session-id string?)
(s/def ::launcher string?)
(s/def ::exit-code int?)
(s/def ::result string?)
(s/def ::error string?)
(s/def ::resumes string?)
(s/def ::identity string?)
(s/def ::updated-at string?)
(s/def ::run-summary
  (s/keys :req-un [::harness/id ::harness/title ::harness/state
                   ::alias ::harness ::mode ::phase ::session-id]
          :opt-un [::launcher ::exit-code ::result ::error ::resumes
                   ::identity ::updated-at]))
(s/def ::runs (s/coll-of ::run-summary :kind vector?))
(s/def ::timed-out (s/coll-of ::harness/id :kind vector?))
(s/def ::await-result
  (s/keys :req-un [::runs ::timed-out]))
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
        :await ::await-result
        :registry ::harness/registry-list
        :agent-list ::agent-list
        :config ::config-result))

(def ^:private agent-prime
  "Terse runbook projected by `strand prime agent`."
  (fmt/prose
   "
     `agent` runs tracked coding agents. The normal path is headless: select an
     available provider harness or alias, run it with a prompt, then await its
     run id.

     List currently usable agents with their resolution, model, thinking level,
     and guidance:

     ```text
     strand agent list
     ```

     Run an agent and collect its work:

     ```sh
     strand agent run <agent> --prompt <prompt>
     strand agent await <run-id>
     ```

     A failed run stays active. Correct its agent selection, cwd, provider
     attributes, or runtime flags and retry it in place. Resume a successful run
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
     complete registry, including unavailable candidates and their reasons.

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
     `retry` reuses a failed tracked run after correction; `resume` creates a
     tracked continuation of a completed provider session.

     Set `--interactive` on `run` or `resume` only when the user asks to work in
     the provider session. It launches the provider in the caller's terminal;
     `resumable` lists completed interactive runs available to continue.
     "
   {}))

(millstrand/defop agent
  "Create and manage tracked coding-agent runs.

  Run, retry, and resume may schedule asynchronous headless work. `await`
  blocks the CLI thread until each requested run is terminal or its timeout
  expires; every other subcommand returns after its immediate transition."
  {:arg-spec cli/agent-arg-spec
   :about agent-about
   :prime agent-prime}
  [{:op/keys [runtime args cwd] :as ctx}]
  (require-valid! ::op-context ctx "agent op received an invalid operation context")
  (require-valid!
   ::op-result
   (case (:subcommand args)
     ["run"] (op-run runtime args cwd)
     ["await"] (await! runtime (:run-ids args) (or (:timeout-secs args) 300))
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
     ["list"] (if (:full args)
                (harness/harnesses runtime)
                (agent-list runtime))
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
  [rt]
  (let [registry (harness/harnesses rt)
        entries (into {} (map (juxt :name identity)) registry)]
    (->> registry
         (filter :available)
         (mapv #(concise-agent-entry rt entries %)))))

(defn- full-run [rt id]
  (or (weaver/show rt id) (fail! "Agent run not found" {:id id})))

(defn- overlay-map [value]
  (cond
    (nil? value) {}
    (map? value) value
    :else (fail! "--attributes must be a JSON object" {:attributes value})))

(defn- summary [run]
  (cond-> {:id (:id run)
           :title (:title run)
           :state (:state run)
           :alias (attr-get run :harness/alias)
           :harness (attr-get run :harness/harness)
           :mode (attr-get run :harness/mode)
           :phase (attr-get run :harness/phase)
           :session-id (attr-get run :harness/session-id)}
    (some? (attr-get run :harness/exit-code))
    (assoc :exit-code (attr-get run :harness/exit-code))
    (attr-get run :harness/result) (assoc :result (attr-get run :harness/result))
    (attr-get run :harness/error) (assoc :error (attr-get run :harness/error))
    (attr-get run :harness/resumes) (assoc :resumes (attr-get run :harness/resumes))
    (attr-get run :identity/id) (assoc :identity (attr-get run :identity/id))
    (:updated_at run) (assoc :updated-at (:updated_at run))))

(defn- resumable-runs [rt]
  (let [runs (weaver/list rt
                          [:and
                           [:= :state "closed"]
                           [:= [:attr "harness/run"] "true"]
                           [:= [:attr "harness/mode"] "interactive"]
                           [:= [:attr "harness/phase"] "done"]]
                          {})
        resumed-run-ids (into #{} (keep #(attr-get % :harness/resumes)) runs)]
    (->> runs
         (remove #(contains? resumed-run-ids (:id %)))
         (sort-by :updated_at #(compare %2 %1))
         (mapv summary))))

(defn- interactive-plan [rt run]
  (assoc (summary run) :launcher (execution/prepare-interactive! rt run)))

(defn- op-run
  [rt {:keys [agent interactive prompt append-system-prompt cwd attributes title]
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
               (some? title) (assoc :title title)))]
    (if interactive
      (interactive-plan rt run)
      (do
        (execution/schedule! rt)
        (summary run)))))

(defn- terminal? [run]
  (#{"done" "failed"} (attr-get run :harness/phase)))

(defn- await!
  "Wait for run IDs to reach done or failed, returning structured summaries."
  [rt ids timeout-secs]
  (let [deadline (+ (System/nanoTime) (* 1000000000 (long timeout-secs)))]
    (loop []
      (let [runs (mapv #(full-run rt %) ids)
            unfinished (remove terminal? runs)]
        (if (or (empty? unfinished) (>= (System/nanoTime) deadline))
          {:runs (mapv summary runs)
           :timed-out (mapv :id unfinished)}
          (do (Thread/sleep 100) (recur)))))))

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
                     rt (select-keys args [:run-id :session-id :identity]))
        run (harness/resume!
             rt (:id predecessor)
             (cond-> {:mode (if (:interactive args) :interactive :headless)}
               (contains? args :prompt) (assoc :prompt (:prompt args))
               (contains? args :cwd) (assoc :cwd (:cwd args))
               (contains? args :attributes)
               (assoc :attributes (overlay-map (:attributes args)))
               (contains? args :title) (assoc :title (:title args))))]
    (if (:interactive args)
      (interactive-plan rt run)
      (do
        (execution/schedule! rt)
        (summary run)))))

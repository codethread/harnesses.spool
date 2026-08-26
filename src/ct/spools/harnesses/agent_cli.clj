(ns ct.spools.harnesses.agent-cli
  "CLI operations and interactive bin declaration for tracked harness runs."
  (:refer-clojure :exclude [agent])
  (:require [clojure.spec.alpha :as s]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.cli :as cli]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(declare ^:private op-run
         ^:private await!
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
(s/def ::op-result
  (s/or :run ::run-summary
        :runs ::runs
        :await ::await-result
        :registry ::harness/registry-list
        :config ::config-result))

(millstrand/defop harness
  "Dispatch parsed `strand harness` subcommands.

  Run, retry, and resume may schedule asynchronous headless work. `await`
  blocks the CLI thread until each requested run is terminal or its timeout
  expires; every other subcommand returns after its immediate transition."
  {:arg-spec cli/harness-arg-spec}
  [{:op/keys [runtime args cwd] :as ctx}]
  (require-valid! ::op-context ctx "harness op received an invalid operation context")
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
     ["list"] (harness/harnesses runtime)
     ["config" "list"] {:flags (harness/flags runtime)}
     ["config" "set"] {:flag (:flag args)
                       :value (harness/set-flag! runtime (:flag args)
                                                 (:value args))}
     ["config" "unset"] {:flag (:flag args)
                         :removed (harness/unset-flag! runtime (:flag args))})
   "harness op produced an invalid result"))

(millstrand/defbin agent
  "Open a coding agent in the caller's terminal as a tracked interactive run."
  {:executable [:root "bin/agent"]})

(defn- full-run [rt id]
  (or (weaver/show rt id) (fail! "Harness run not found" {:id id})))

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
  [rt {:keys [harness interactive prompt cwd attributes title] :as args} op-cwd]
  (let [effort (if (contains? args :effort) (:effort args) (:thinking args))
        attributes (cond-> (overlay-map attributes)
                     (some? effort) (assoc :harness/effort effort))
        run (harness/create!
             rt
             (cond-> {:harness harness
                      :mode (if interactive :interactive :headless)
                      :cwd (or cwd op-cwd)
                      :attributes attributes}
               (some? prompt) (assoc :prompt prompt)
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
      (contains? args :harness) (assoc :harness (:harness args))
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

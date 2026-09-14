(ns ct.spools.harnesses.execution
  "Asynchronous and interactive execution for provider-neutral harness runs."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.assignment :as assignment]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-receipts :as guidance-receipts]
            [ct.spools.harnesses.internal.launcher :as launcher]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-startup :as managed]
            [ct.spools.harnesses.internal.process-custody :as custody]
            [ct.spools.harnesses.internal.runs :as runs]
            [ct.spools.harnesses.reconciliation :as reconciliation]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.time Instant]
           [java.util UUID]
           [java.util.concurrent Executors ThreadFactory TimeUnit]))

(def ^:private state-version 4)
(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned})

(declare schedule! inspect-owned! launch-in-flight?
         ^:private finish-process! ^:private state
         ^:private activate-state! ^:private deactivate-state!
         ^:private ready-headless ^:private claim! ^:private release!
         ^:private launch-headless! ^:private full-run
         ^:private resolved-definition ^:private prepare-launch
         ^:private enforce-stop! ^:private schedule-inspection!
         ^:private schedule-guidance-deadline!
         ^:private arm-guidance-deadline!
         ^:private recover-guidance-deadlines! ^:private callback)

(s/def ::event
  (s/and map?
         #(keyword? (:event/type %))
         #(contains? % :event/id)))
(s/def ::claimed-run-ids (s/coll-of ::harness/id :kind vector?))

(millstrand/defhandler on-event
  "Schedule newly ready headless runs after a graph event.

  Claims eligible runs, submits each to the daemon executor, and returns their
  IDs without waiting for the launched processes to finish."
  {:types event-types
   :metadata {:spool "harnesses"}}
  [event]
  (require-valid! ::event event "Harness event handler received an invalid event")
  (schedule! (current/runtime)))

(s/fdef on-event
  :args (s/cat :event ::event)
  :ret ::claimed-run-ids)

(defn open-execution!
  "Open harness execution resources and recover existing work.

  Custody reconciliation is attempted but is not a precondition of opening.
  Mill's process registry is not necessarily readable at the moment execution
  opens — during Weaver startup it is not yet admitting requests — and a
  workspace holding old running rows must still be able to boot so it can
  reconcile them. The deferred error is retained and the recurring
  reconcile pass retries."
  [{:keys [runtime]}]
  (require-valid! ::harness/runtime runtime
                  "harness execution open received an invalid runtime")
  (let [opened (activate-state! runtime)]
    (try
      (harness/migrate-runs! runtime)
      (let [recovery (try
                       (inspect-owned! runtime)
                       nil
                       (catch Throwable error
                         (reset! (:deferred-recovery opened) error)
                         error))
            _ (recover-guidance-deadlines! runtime)]
        (cond-> {:opened :harness-execution
                 :claimed (schedule! runtime)}
          recovery (assoc :deferred-recovery (ex-message recovery))))
      (catch Throwable error
        (try
          ((:close-fn (deactivate-state! runtime)))
          (catch Throwable close-error
            (.addSuppressed error close-error)))
        (throw error)))))

(defn close-execution!
  "Stop harness execution resources."
  [{:keys [runtime]}]
  ((:close-fn (deactivate-state! runtime)))
  {:closed :harness-execution})

(defn schedule!
  "Claim and asynchronously launch every published, ready headless run."
  [rt]
  (let [claimed (filterv #(claim! rt (:id %)) (ready-headless rt))
        executor (:executor (state rt))]
    (doseq [run claimed]
      (.execute executor ^Runnable #(launch-headless! rt (:id run))))
    (mapv :id claimed)))

(s/fdef schedule!
  :args (s/cat :runtime ::harness/runtime)
  :ret ::claimed-run-ids)

(defn prepare-interactive!
  "Prepare an interactive run and return its private launcher path."
  [rt run]
  (try
    (let [definition (resolved-definition rt run)
          launch-spec (prepare-launch rt definition run)]
      (launcher/write! rt run (:argv launch-spec) (:env launch-spec)))
    (catch Exception e
      (harness/finish! rt (:id run) {:status :failed
                                     :evidence {:settled true
                                                :settlement "launch-not-started"
                                                :failure-class "launch"}
                                     :error (str (ex-message e)
                                                 (when-let [data (ex-data e)]
                                                   (str " " (pr-str data))))})
      (throw e))))

(defn mark-interactive-running!
  "Start an interactive run and arm its managed bootstrap.

  When supplied, `completion-owner-pid` records the callback owner's exact
  process identity in the same fenced attempt transition."
  ([rt id]
   (mark-interactive-running! rt id nil))
  ([rt id completion-owner-pid]
   (let [run (full-run rt id)]
     (when-not (= "interactive" (attr-get run :harness/mode))
       (fail! "_started applies only to interactive harness runs" {:id id}))
     (let [owner-attributes
           (if completion-owner-pid
             (reconciliation/completion-owner-attributes completion-owner-pid)
             {})
           {:keys [strand invocation]}
           (harness/begin-attempt! rt id owner-attributes)]
       (try
         (when-let [bootstrap (managed/bootstrap rt strand)]
           (launcher/arm!
            rt strand bootstrap
            (when (some? (attr-get strand :harness/guidance-version))
              (guidance/bootstrap strand))))
         (when (guidance/native? strand)
           (schedule-guidance-deadline! rt strand))
         strand
         (catch Throwable error
           (harness/finish!
            rt id
            {:status :failed
             :invocation invocation
             :evidence {:settled true
                        :settlement "launch-not-started"
                        :failure-class "launch"}
             :error (str "Unable to arm managed launcher: "
                         (ex-message error))})
           (throw error)))))))

(defn- legacy-interactive-callback-attempt? [run]
  (let [contract (attr-get run :harness/interactive-callback-contract)
        current (life/invocation run)]
    (and (pos-int? (attr-get run :harness/attempt))
         (not (str/blank? current))
         (or (= "legacy" contract)
             (and (nil? contract)
                  (str/blank?
                   (attr-get run :harness/completion-owner-invocation)))))))

(defn- originating-interactive-invocation [run invocation callback]
  (if (some? invocation)
    invocation
    (if (legacy-interactive-callback-attempt? run)
      (life/invocation run)
      (fail! "Invocation-less interactive callback requires a legacy attempt"
             {:id (:id run)
              :callback callback
              :attempt (attr-get run :harness/attempt)
              :callback-contract
              (attr-get run :harness/interactive-callback-contract)}))))

(defn mark-interactive-provider!
  "Bind the actual provider exec to its interactive attempt.

  Legacy launchers may omit `invocation` only when the durable attempt records
  the legacy callback contract. Current v2 launchers must supply the exact
  invocation."
  [rt id invocation provider-pid]
  (let [run (full-run rt id)]
    (when-not (= "interactive" (attr-get run :harness/mode))
      (fail! "_provider_started applies only to interactive harness runs"
             {:id id}))
    (reconciliation/register-provider!
     rt id
     (originating-interactive-invocation run invocation "_provider_started")
     provider-pid)))

(defn finish-interactive!
  "Finish an interactive run through its fenced provider callback.

  Legacy bins may omit `invocation` only for a durable legacy callback attempt.
  Current v2 attempts require their exact invocation before provider outcome
  processing begins. The serialized core transition checks the token again
  after provider outcome processing."
  [rt id invocation exit-code]
  (let [run (full-run rt id)]
    (when-not (= "interactive" (attr-get run :harness/mode))
      (fail! "_finished applies only to interactive harness runs" {:id id}))
    (let [invocation
          (originating-interactive-invocation run invocation "_finished")]
      (if (not= invocation (life/invocation run))
        run
        (let [definition (resolved-definition rt run)
              {:keys [outcome provider-error?]}
              (try
                {:outcome
                 ((callback (:finish definition))
                  rt definition run
                  {:exit-code exit-code :stdout nil :stderr nil})}
                (catch Exception e
                  {:provider-error? true
                   :outcome
                   {:status :failed
                    :exit-code exit-code
                    :error (str (ex-message e)
                                (when-let [data (ex-data e)]
                                  (str " " (pr-str data))))}}))
              evidence (cond-> (life/settlement-evidence
                                {:exit-code exit-code})
                         provider-error? (assoc :failure-class "execution"))
              outcome (assoc outcome :invocation invocation)
              current-run (full-run rt id)]
          (if (and (guidance/native? current-run)
                   (= "bootstrap" (life/substatus current-run)))
            (harness/settle-outcome! rt id outcome evidence)
            (harness/finish! rt id (assoc outcome :evidence evidence))))))))

(defn launch-in-flight?
  "Return whether this worker still owns an unfinished launch for `run`.

  The claim covers the window between minting the attempt and Mill returning a
  listable custody record. It is scoped to the one run: an unrelated in-flight
  launch must never stop another run from being reconciled."
  [rt run]
  (and (contains? #{nil "pending"} (attr-get run :harness/process-handle))
       (contains? @(:in-flight (state rt)) (:id run))))

(defn inspect-owned!
  "Inspect and advance headless runs backed by Mill process custody."
  [rt]
  (let [owned (runs/inspectable-headless rt #(launch-in-flight? rt %))]
    (when (seq owned)
      (let [records (custody/list-owned rt)
            recur? (atom false)
            transition-errors (atom [])
            failures (:reconciliation-failures (state rt))]
        (doseq [run owned]
          (try
            (let [run (if (guidance/native? run)
                        (guidance-receipts/expire! rt run)
                        run)
                  record (custody/record-for "harness" run records)
                  durable (custody/durable-attributes "harness"
                                                      (:id run)
                                                      (attr-get run :harness/attempt)
                                                      record)]
              (swap! failures dissoc (:id run))
              (when (= "pending" (attr-get run :harness/process-handle))
                (weaver/update! rt (:id run) {:attributes durable}))
              (if (= :terminal (:phase record))
                (finish-process! rt (full-run rt (:id run))
                                 (resolved-definition rt (full-run rt (:id run)))
                                 record)
                (do
                  (enforce-stop! rt run record)
                  (reset! recur? true))))
            (catch Throwable error
              (let [id (:id run)
                    message (str "process custody reconciliation failed: "
                                 (ex-message error) " " (pr-str (ex-data error)))
                    record (some #(when (= (:key %) (attr-get run :harness/process-key)) %)
                                 records)
                    signature [(:id run) (attr-get run :harness/process-key) message]
                    repeated? (and (nil? record)
                                   (life/terminal? run)
                                   (not (life/settled? run))
                                   (= signature (get @failures id)))
                    transition-error
                    (when-not repeated?
                      (try
                        (harness/finish!
                         rt id
                         (cond-> {:status :failed
                                  :evidence
                                  {:settled false
                                   :settlement "no-terminal-evidence"
                                   :failure-class "reconciliation"}
                                  :error message}
                           (some? (life/invocation run))
                           (assoc :invocation (life/invocation run))))
                        (swap! failures assoc id signature)
                        nil
                        (catch Throwable transition-error
                          transition-error)))]
                (release! rt id)
                (when transition-error
                  (when (and record
                             (not= :terminal (:phase record))
                             (= "running" (life/status (full-run rt id))))
                    (reset! recur? true))
                  (swap! transition-errors conj
                         (ex-info "Unable to persist harness custody failure"
                                  {:run-id id
                                   :reconciliation-error {:run-id id
                                                          :message (ex-message error)
                                                          :data (ex-data error)}
                                   :failure-transition-error
                                   {:message (ex-message transition-error)
                                    :data (ex-data transition-error)}}
                                  transition-error)))))))
        (when @recur?
          (schedule-inspection! rt))
        (when (seq @transition-errors)
          (if (= 1 (count @transition-errors))
            (throw (first @transition-errors))
            (throw (ex-info "Unable to persist harness custody failures"
                            {:failure-transition-errors
                             (mapv ex-data @transition-errors)}
                            (first @transition-errors)))))))))

(defn- daemon-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "harness-worker")
        (.setDaemon true)))))

(defn- new-state []
  (let [executor (Executors/newCachedThreadPool (daemon-thread-factory))
        scheduler (java.util.concurrent.ScheduledThreadPoolExecutor. 1
                                                                     (daemon-thread-factory))
        open? (atom true)]
    {:generation (str (UUID/randomUUID))
     :open? open?
     :in-flight (atom #{})
     :deferred-recovery (atom nil)
     :reconciliation-failures (atom {})
     :inspection-scheduled? (atom false)
     :executor executor
     :scheduler scheduler
     :close-fn (fn []
                 (locking open?
                   (reset! open? false)
                   (.shutdownNow executor)
                   (.shutdownNow scheduler))
                 (.awaitTermination executor 1000 TimeUnit/MILLISECONDS))}))

(defn- state-holder [rt]
  (runtime/spool-state rt ::state {:version state-version}
                       #(hash-map :active (atom nil))))

(defn- state [rt]
  (or @(:active (state-holder rt))
      (fail! "Harness execution resources are not open" {})))

(defn- activate-state! [rt]
  (let [active (:active (state-holder rt))
        opened (new-state)]
    (when-not (compare-and-set! active nil opened)
      ((:close-fn opened))
      (fail! "Harness execution resources are already open" {}))
    opened))

(defn- deactivate-state! [rt]
  (let [active (:active (state-holder rt))
        opened @active]
    (when-not opened
      (fail! "Harness execution resources are not open" {}))
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (:open? opened)
      (when-not (and @(:open? opened)
                     (compare-and-set! active opened nil))
        (fail! "Harness execution resources are not open" {}))
      (reset! (:open? opened) false))
    opened))

(defn- eligible-guidance-deadline? [run record]
  (and (= "running" (life/status run))
       (= "interactive" (attr-get run :harness/mode))
       (guidance/native? run)
       (contains? #{"pending" "fetched"} (get record "state"))
       (not (and (= "pi" (attr-get run :harness/harness))
                 (= "fetched" (get record "state"))))))

(defn- active-generation [rt generation]
  (let [opened @(:active (state-holder rt))]
    (when (and opened
               @(:open? opened)
               (= generation (:generation opened)))
      opened)))

(defn- schedule-guidance-task! [rt originating-run generation delay-nanos]
  (when-let [opened (active-generation rt generation)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (:open? opened)
      (when (and @(:open? opened)
                 (identical? opened (active-generation rt generation)))
        (.schedule
         ^java.util.concurrent.ScheduledExecutorService (:scheduler opened)
         ^Runnable #(arm-guidance-deadline! rt originating-run generation)
         (max 1 delay-nanos)
         TimeUnit/NANOSECONDS)
        :scheduled))))

(defn- same-guidance-attempt? [originating-run current]
  (and (= (attr-get originating-run :harness/attempt)
          (attr-get current :harness/attempt))
       (= (attr-get originating-run :harness/invocation)
          (attr-get current :harness/invocation))))

(defn- arm-guidance-deadline! [rt originating-run generation]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (when (active-generation rt generation)
      (let [current (full-run rt (:id originating-run))
            record (guidance/current-attempt current)]
        (when (and (same-guidance-attempt? originating-run current)
                   (eligible-guidance-deadline? current record))
          (let [now (Instant/now)]
            (if (guidance/deadline-expired? current record now)
              (do
                (guidance-receipts/expire! rt originating-run)
                :expired)
              (let [deadline (Instant/parse (get record "deadline-at"))
                    delay-nanos (.toNanos (java.time.Duration/between
                                           now deadline))]
                (schedule-guidance-task! rt originating-run generation
                                         delay-nanos)))))))))

(defn- schedule-guidance-deadline! [rt run]
  (when-let [opened @(:active (state-holder rt))]
    (arm-guidance-deadline! rt run (:generation opened))))

(defn- recover-guidance-deadlines! [rt]
  (->> (weaver/list rt)
       (keep #(schedule-guidance-deadline! rt %))
       count))

(defn- schedule-inspection! [rt]
  (let [{:keys [inspection-scheduled? scheduler]} (state rt)]
    (when (compare-and-set! inspection-scheduled? false true)
      (.schedule ^java.util.concurrent.ScheduledExecutorService scheduler
                 ^Runnable #(do
                              (reset! inspection-scheduled? false)
                              (inspect-owned! rt))
                 100 TimeUnit/MILLISECONDS))))

(defn- callback [symbol]
  (or (requiring-resolve symbol)
      (fail! "Harness callback cannot be resolved" {:callback symbol})))

(defn- run? [run]
  (= "true" (attr-get run :harness/run)))

(defn- ready-headless
  "Return runs eligible to launch.

  Publication is the gate: an unpublished run is still being created and has no
  identity, target link, or request binding yet, so it must never be launched
  or reconciled by anyone."
  [rt]
  (filterv #(and (run? %)
                 (life/published? %)
                 (= "ready" (life/status %))
                 (= "headless" (attr-get % :harness/mode))
                 (assignment/launch-ready? rt %))
           (weaver/ready rt)))

(defn- claim! [rt id]
  (let [[before _] (swap-vals! (:in-flight (state rt)) conj id)]
    (not (contains? before id))))

(defn- release! [rt id]
  (swap! (:in-flight (state rt)) disj id))

(defn- full-run [rt id]
  (or (weaver/show rt id) (fail! "Harness run not found" {:id id})))

(defn- resolved-definition [rt run]
  (harness/concrete-harness rt (attr-get run :harness/harness)))

(defn- prepare-launch [rt definition run]
  (let [launch-spec ((callback (:prepare definition)) rt definition run)
        alias-env (into {}
                        (map (fn [[name value]]
                               [(clojure.core/name name) value]))
                        (or (attr-get run :harness/env) {}))]
    (require-valid!
     ::harness/launch-spec
     (update launch-spec :env #(merge alias-env (or % {})))
     "Harness prepare must return a valid launch specification")))

(defn- process-spec [rt run {:keys [argv env stdin]}]
  (let [bootstrap (managed/bootstrap rt run)
        guidance-document
        (when (and bootstrap
                   (some? (attr-get run :harness/guidance-version)))
          (guidance/bootstrap run))]
    {:argv argv
     :cwd (attr-get run :harness/cwd)
     :env (cond-> (assoc (or env {})
                         "MILLSTRAND_RUN_ID" (:id run)
                         "MILLSTRAND_WORKSPACE" (launcher/workspace rt))
            (attr-get run :identity/id)
            (assoc "MILLSTRAND_AGENT_ID" (attr-get run :identity/id))
            bootstrap
            (assoc "MILLSTRAND_MANAGED_BOOTSTRAP"
                   (json/write-str bootstrap))
            guidance-document
            (assoc "MILLSTRAND_MANAGED_GUIDANCE"
                   (json/write-str guidance-document)))
     :stdin stdin}))

(defn- finish-process!
  "Record one terminal custody fact as a fenced outcome plus settlement evidence.

  Evidence is derived from the raw observation, before the exit code is
  defaulted for the provider callback: a cancellation with no retained exit is
  the case where the provider's own backend may still hold the session, and
  flattening it to `exit 1` would forge the proof that it does not."
  [rt run definition record]
  (weaver/update! rt (:id run)
                  {:attributes (custody/durable-attributes "harness"
                                                           (:id run)
                                                           (attr-get run :harness/attempt)
                                                           record)})
  (let [raw (custody/terminal-observed record)
        evidence (cond-> (life/settlement-evidence raw)
                   (:cancellation raw) (assoc :cancelled? true))
        observed (select-keys raw [:exit-code :stdout :stderr])
        observed (if (some? (:exit-code observed))
                   observed
                   (assoc observed :exit-code 1
                          :stderr (or (:stderr observed)
                                      (custody/terminal-error raw)
                                      "Process custody terminal failure")))
        outcome (assoc ((callback (:finish definition))
                        rt definition run observed)
                       :invocation (life/invocation run))]
    (if (life/terminal? (full-run rt (:id run)))
      (harness/settle-outcome! rt (:id run) outcome evidence)
      (harness/finish! rt (:id run)
                       (assoc outcome :evidence evidence)))
    (custody/acknowledge! rt record)))

(defn- enforce-stop!
  "Ask Mill to cancel one run's owned process tree when a stop is outstanding.

  The process is addressed only by its owner, key, and opaque handle. No PID is
  ever guessed, so a recycled PID cannot be signalled by mistake."
  [rt run record]
  (when (and (life/stop-requested? run) (not= :terminal (:phase record)))
    (custody/cancel! rt record)))

(defn stop!
  "Request a durable stop of one run and enforce it against process custody.

  The durable request is recorded first, so intent survives a crash between
  recording and killing. The run stays `running` until a terminal custody fact
  settles it: asking a process to stop is not evidence that it has."
  [rt id request]
  (let [stopped (harness/stop! rt id request)]
    (when (= "running" (life/status stopped))
      (when-let [record (try
                          (custody/record-for "harness" stopped
                                              (custody/list-owned rt))
                          (catch Throwable _
                            ;; No readable custody fact yet. The recurring
                            ;; inspection re-enforces the durable request.
                            nil))]
        (enforce-stop! rt stopped record))
      (inspect-owned! rt))
    stopped))

(defn- launch-headless!
  "Launch one already-claimed pending headless run."
  [rt id]
  (try
    (let [candidate (full-run rt id)]
      ;; The ready set is only a snapshot. Recheck both the run phase and its
      ;; target while holding the scheduler claim so a delayed worker cannot
      ;; start, or fail, the attempt already owned by a newer worker.
      (when-not (and (= "ready" (life/status candidate))
                     (assignment/launch-ready? rt candidate))
        (throw (ex-info "Harness run is no longer ready to launch"
                        {:run-id id :deferred true}))))
    ;; The attempt and its fencing invocation are minted in one durable
    ;; transition. Only failures after this point belong to this worker, and
    ;; they are published with this exact invocation rather than one read from
    ;; mutable durable state.
    (let [{:keys [attempt invocation]} (harness/begin-attempt! rt id)]
      (try
        (let [run (full-run rt id)
              definition (resolved-definition rt run)
              launch-spec (prepare-launch rt definition run)
              _ (weaver/update! rt id
                                {:attributes
                                 (custody/durable-attributes
                                  "harness" id attempt
                                  {:handle "pending" :phase :starting})})
              record (custody/launch! rt id attempt
                                      (process-spec rt run launch-spec))]
          (weaver/update! rt id
                          {:attributes
                           (custody/durable-attributes "harness" id attempt record)})
          (if (= :terminal (:phase record))
            (finish-process! rt (full-run rt id) definition record)
            (do
              (enforce-stop! rt (full-run rt id) record)
              (inspect-owned! rt)))
          invocation)
        (catch Exception e
          (if (life/terminal? (full-run rt id))
            (throw e)
            (let [current (full-run rt id)
                  error-data (ex-data e)
                  message (str (ex-message e)
                               (when error-data
                                 (str " " (pr-str error-data))))
                  evidence
                  (if (= "process/malformed-launch" (:code error-data))
                    (assoc (life/settlement-evidence
                            {:launch-failure error-data})
                           :failure-class "launch")
                    {:settled false
                     :settlement "no-terminal-evidence"
                     :failure-class
                     (if (attr-get current :harness/process-handle)
                       "execution"
                       "launch")})
                  transition-error
                  (try
                    (harness/finish!
                     rt id
                     {:status :failed
                      :invocation invocation
                      :evidence evidence
                      :error message})
                    nil
                    (catch Throwable finish-error finish-error))]
              (when transition-error
                (throw (ex-info "Unable to persist harness launch failure"
                                {:run-id id
                                 :launch-error {:message (ex-message e)
                                                :data (ex-data e)}
                                 :failure-transition-error
                                 {:message (ex-message transition-error)
                                  :data (ex-data transition-error)}}
                                transition-error))))))))
    (catch Exception e
      (when-not (:deferred (ex-data e))
        (throw e)))
    (finally
      (release! rt id)
      (inspect-owned! rt)
      (schedule! rt))))

(lifecycle/defresource harness-execution-runtime
  "Own asynchronous and interactive harness execution resources."
  {:open 'ct.spools.harnesses.execution/open-execution!
   :close 'ct.spools.harnesses.execution/close-execution!
   :after #{:assignment-runtime
            :claude-harness-runtime
            :codex-harness-runtime
            :cursor-harness-runtime
            :pi-harness-runtime}})

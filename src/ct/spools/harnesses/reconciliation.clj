(ns ct.spools.harnesses.reconciliation
  "Supported inspection and abandonment of orphaned interactive runs.

  Interactive providers run in a user's terminal rather than Mill process
  custody. New Codex and Pi attempts therefore record the completion owner and
  actual provider exec with server-observed PID/start fences. Reconciliation
  never signals either PID or invents process-exit facts."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.attribution :as attribution]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.reconciliation :as decision]
            [ct.spools.harnesses.internal.reconciliation-process :as process]
            [ct.spools.harnesses.internal.reconciliation-sweep :as sweep]
            [ct.spools.harnesses.internal.runs :as runs]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.util UUID]))

(def default-sweep-interval-ms
  "Default interval between durable orphan-reconciliation sweeps."
  (* 60 60 1000))

(def sweep-environment-variable
  "Environment variable overriding the positive sweep interval in milliseconds."
  "MILLSTRAND_HARNESS_RECONCILIATION_INTERVAL_MS")

(def ^:private sweep-limit 100)

(s/def ::runtime map?)
(s/def ::run-id (s/and string? (complement str/blank?)))
(s/def ::dry-run? boolean?)
(s/def ::abandon? boolean?)
(s/def ::reason (s/and string? (complement str/blank?)))
(s/def ::by-identity (s/and string? (complement str/blank?)))
(s/def ::source (s/and string? (complement str/blank?)))
(s/def ::limit pos-int?)
(s/def ::offset nat-int?)
(s/def ::generation (s/and string? (complement str/blank?)))
(s/def ::options
  (s/and (s/keys :opt-un [::run-id ::dry-run? ::abandon? ::reason
                          ::by-identity ::source ::limit ::offset])
         #(every? #{:run-id :dry-run? :abandon? :reason :by-identity :source
                    :limit :offset}
                  (keys %))))
(s/def ::interval-ms pos-int?)

(defn- parse-sweep-interval [configured]
  (cond
    (nil? configured) default-sweep-interval-ms
    (= "disabled" configured) nil
    :else
    (let [parsed (try
                   (Long/parseLong configured)
                   (catch NumberFormatException _
                     (fail! "Harness reconciliation interval must be an integer"
                            {:environment-variable sweep-environment-variable
                             :value configured})))]
      (when-not (pos? parsed)
        (fail! "Harness reconciliation interval must be positive"
               {:environment-variable sweep-environment-variable
                :value configured}))
      parsed)))

(defn configured-sweep-interval-ms
  "Return the strictly parsed sweep interval for this Weaver process.

  `MILLSTRAND_HARNESS_RECONCILIATION_INTERVAL_MS` overrides the one-hour
  default. The exact value `disabled` explicitly disables scheduling. Other
  invalid and non-positive values fail module activation rather than silently
  changing cadence."
  []
  (parse-sweep-interval (System/getenv sweep-environment-variable)))

(def process-identity
  "Return the current non-signalling identity observation for local PID `pid`."
  process/process-identity)

(def completion-owner-observation
  "Observe the exact completion-owning bin process without signalling it."
  process/completion-owner-observation)

(def provider-observation
  "Observe the exact provider exec process without signalling it."
  process/provider-observation)

(def native-observation
  "Return positive local evidence of a process naming the run's native session."
  process/native-observation)

(def completion-owner-attributes
  "Return durable start attributes for the current completion-owning bin PID."
  process/completion-owner-attributes)

(defn register-provider!
  "Bind the actual provider-exec PID to one running interactive invocation.

  The generated child shell reports itself immediately before `exec`, so the
  PID and start instant remain stable across the exec. Repeats with identical
  evidence converge; conflicts and stale invocations fail loudly. The execution
  boundary resolves any accepted legacy callback before calling this function."
  [rt id invocation pid]
  (require-valid! ::runtime rt "register-provider! requires a Weaver runtime")
  (require-valid! ::run-id id "register-provider! requires a run ID")
  (require-valid! ::run-id invocation
                  "register-provider! requires an invocation")
  (require-valid! pos-int? pid "register-provider! requires a positive PID")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)
          fact (process-identity pid)
          host (process/scoped-host-observation)
          existing-invocation (attr-get run :harness/provider-invocation)
          existing-identity
          (select-keys (:attributes run)
                       [:harness/provider-pid
                        :harness/provider-started-at
                        :harness/provider-host])
          observed-identity
          {:harness/provider-pid pid
           :harness/provider-started-at (:started-at fact)
           :harness/provider-host (:host host)}]
      (when-not (= "interactive" (attr-get run :harness/mode))
        (fail! "Provider registration applies only to interactive runs" {:id id}))
      (when-not (and (= "running" (life/status run))
                     (= invocation (life/invocation run)))
        (fail! "Provider registration has a stale interactive invocation"
               {:id id :invocation invocation
                :current-invocation (life/invocation run)
                :status (life/status run)}))
      (when-not (and (= "live" (:state fact))
                     (= "available" (:state host)))
        (fail! "Interactive provider exec is not positively observable"
               {:id id :process fact :host host}))
      (when (and existing-invocation
                 (or (not= existing-invocation invocation)
                     (not= existing-identity observed-identity)))
        (fail! "Interactive provider evidence conflicts with its invocation"
               {:id id :invocation invocation
                :existing-invocation existing-invocation
                :existing-identity existing-identity
                :observed-identity observed-identity}))
      (weaver/update!
       rt id
       {:attributes (assoc observed-identity
                           :harness/provider-invocation invocation)}))))

(defn- active-session-writers [rt run]
  (let [session-id (attr-get run :harness/session-id)]
    (if (str/blank? session-id)
      []
      (->> (runs/reserving-session-writers rt session-id)
           (filter life/active?)
           (remove #(= (:id run) (:id %)))
           (mapv :id)))))

(defn- unavailable-probe [error]
  {:state "unavailable"
   :reason (ex-message error)
   :data (ex-data error)})

(defn- observe-process [probe]
  (try
    (probe)
    (catch Throwable error
      (unavailable-probe error))))

(defn- observe-session-writers [rt run]
  (try
    {:state "available"
     :runs (active-session-writers rt run)}
    (catch Throwable error
      (unavailable-probe error))))

(defn- evidence [rt run]
  (let [session-writers (observe-session-writers rt run)]
    {:observed-at (str (runtime/now rt))
     :completion-owner
     (observe-process #(completion-owner-observation run))
     :provider (observe-process #(provider-observation run))
     :native (observe-process #(native-observation run))
     :session-writers session-writers
     :active-session-writers (or (:runs session-writers) [])
     :attempt (attr-get run :harness/attempt)
     :invocation (life/invocation run)}))

(defn- inspection-report [run observed classified]
  (merge {:id (:id run)
          :status (life/status run)
          :substatus (life/substatus run)
          :target (attr-get run :harness/target)
          :settled (life/settled? run)
          :evidence observed}
         classified))

(defn- inspect-one [rt run]
  (let [without-probes (decision/classification run {})]
    (if (contains? #{"terminal" "ineligible"}
                   (:classification without-probes))
      (inspection-report run {} without-probes)
      (let [observed (evidence rt run)]
        (inspection-report run observed
                           (decision/classification run observed))))))

(defn- rotate-candidates [candidates offset]
  (if (seq candidates)
    (let [split (mod (or offset 0) (count candidates))]
      (concat (drop split candidates) (take split candidates)))
    candidates))

(defn inspect
  "Inspect active interactive runs without changing lifecycle state.

  With `:run-id`, inspect exactly that run, including an already terminal run.
  Without it, inspect published running Codex and Pi attempts only."
  ([rt] (inspect rt {}))
  ([rt {:keys [run-id limit offset] :as opts}]
   (require-valid! ::runtime rt "inspect requires a Weaver runtime")
   (require-valid! ::options opts "inspect requires valid options")
   (let [candidates
         (if run-id
           [(runs/require-run rt run-id)]
           (runs/runs-where
            rt [[:= [:attr "harness/mode"] "interactive"]
                [:in [:attr "harness/harness"] ["codex" "pi"]]
                [:= [:attr "harness/published"] "true"]
                [:= [:attr "harness/status"] "running"]]))]
     (mapv (fn [run]
             (when-not (= "interactive" (attr-get run :harness/mode))
               (fail! "Interactive reconciliation cannot inspect a headless run"
                      {:id (:id run)}))
             (inspect-one rt run))
           (cond->> (rotate-candidates
                     (sort-by (juxt :created_at :id) candidates)
                     offset)
             limit (take limit))))))

(defn- abandon-one! [rt initial opts]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt (:id initial))
          report (inspect-one rt run)
          action (decision/action report opts)
          report (cond-> (assoc report :action action :changed false)
                   (= "abandon" action)
                   (assoc :consequences
                          {:settled false
                           :native-resume false
                           :session-reserved true
                           :target-reserved (some? (attr-get run
                                                             :harness/target))}))]
      (if (or (:dry-run? opts) (not= "abandon" action))
        report
        (let [operator? (:abandon? opts)
              reason (or (:reason opts) (:reason report))
              source (or (:source opts) (if operator? "attested" "manual"))
              by-identity (or (:by-identity opts) source)
              at (str (runtime/now rt))
              patch (decision/abandonment-patch
                     run {:at at :by by-identity :reason reason :source source
                          :evidence (:evidence report)})]
          (attribution/update-with-action!
           rt (:id run) patch "abandoned" (:by-identity opts)
           {:harness/action-reason reason
            :harness/reconciliation-source source})
          (assoc report :changed true
                 :abandoned-at at :abandoned-by by-identity
                 :abandon-reason reason))))))

(defn- explicit-abandonment-eligible? [report]
  (or (contains? #{"unknown" "orphaned"} (:classification report))
      (and (= "terminal" (:classification report))
           (= "abandoned" (:substatus report)))))

(defn reconcile!
  "Reconcile interactive active projections from honest current evidence.

  Proven completion-owner and provider-exec loss records an explicit
  `stopped/abandoned` outcome without claiming settlement or an exit code.
  Unknown evidence is preserved unless an
  operator names exactly one run with `:abandon? true` and a nonblank `:reason`.
  Live, idle, remote, unavailable, and conflicting newer sessions are always
  preserved. `:dry-run? true` returns the same decisions without writes.

  Repeating reconciliation is idempotent: an abandoned run is already terminal
  and receives no second audit transition."
  ([rt] (reconcile! rt {}))
  ([rt {:keys [run-id abandon? reason offset] :as opts}]
   (require-valid! ::runtime rt "reconcile! requires a Weaver runtime")
   (require-valid! ::options opts "reconcile! requires valid options")
   (when (and abandon? (nil? run-id))
     (fail! "Explicit abandonment requires one exact run ID" {}))
   (when (and abandon? (str/blank? reason))
     (fail! "Explicit abandonment requires a nonblank reason" {:run-id run-id}))
   (when (and abandon? (str/blank? (:by-identity opts)))
     (fail! "Explicit abandonment requires an actor identity" {:run-id run-id}))
   (let [limit (when-not run-id (or (:limit opts) sweep-limit))
         inspected (inspect rt (cond-> (select-keys opts [:run-id :offset])
                                 limit (assoc :limit (inc limit))))
         truncated? (and limit (> (count inspected) limit))
         reports (if limit (vec (take limit inspected)) inspected)
         report (first reports)
         _ (when (and abandon?
                      (not (explicit-abandonment-eligible? report)))
             (fail! "Explicit abandonment refuses known live or ineligible evidence"
                    {:run-id run-id :report report}))
         results (mapv #(abandon-one! rt % opts) reports)]
     {:dry-run (true? (:dry-run? opts))
      :limit limit
      :offset (or offset 0)
      :next-offset (when (and limit (seq reports))
                     (mod (+ (or offset 0) (count reports))
                          Long/MAX_VALUE))
      :truncated (boolean truncated?)
      :runs results
      :changed (mapv :id (filter :changed results))})))

(defn desired-sweep
  "Lifecycle read hook returning the configured reconciliation cadence."
  [_context]
  (let [interval-ms (configured-sweep-interval-ms)]
    {:enabled (some? interval-ms)
     :interval-ms interval-ms}))

(defn actual-sweep
  "Lifecycle read hook returning the currently pending durable sweep wake."
  [{:keys [runtime]}]
  (sweep/pending runtime))

(defn apply-sweep!
  "Lifecycle apply hook converging one durable reconciliation wake."
  [{:keys [runtime desired]}]
  (let [config (sweep/config-lock runtime)]
    #_{:splint/disable [lint/locking-object]}
    (locking config
      (let [actual (sweep/pending runtime)
            interval-ms (:interval-ms desired)
            current-interval (get-in actual [:payload :interval-ms])
            current-offset (get-in actual [:payload :offset])
            current-generation (get-in actual [:payload :generation])
            unchanged? (and (= sweep/handler (:handler actual))
                            (= interval-ms current-interval)
                            (nat-int? current-offset)
                            (s/valid? ::generation current-generation))]
        (cond
          (not (:enabled desired))
          (do
            (reset! config desired)
            (when actual
              (sweep/cancel! runtime))
            {:reconciled :harness-interactive-sweep
             :interval-ms nil
             :wake :disabled})

          unchanged?
          (do
            (reset! config (assoc desired :generation current-generation))
            {:reconciled :harness-interactive-sweep
             :interval-ms interval-ms
             :wake :preserved})

          :else
          (let [generation (str (UUID/randomUUID))]
            (reset! config (assoc desired :generation generation))
            (sweep/arm! runtime interval-ms 0 generation)
            {:reconciled :harness-interactive-sweep
             :interval-ms interval-ms
             :wake :scheduled}))))))

(defn remove-sweep!
  "Cancel the sweep on declaration removal while preserving runtime restarts."
  [{:keys [runtime] :effect/keys [phase]}]
  (if (= :runtime-stop phase)
    {:reconciled :harness-interactive-sweep :status :preserved}
    (let [config (sweep/config-lock runtime)]
      #_{:splint/disable [lint/locking-object]}
      (locking config
        (reset! config nil)
        (when (sweep/pending runtime)
          (sweep/cancel! runtime))
        {:reconciled :harness-interactive-sweep :status :removed}))))

(defn sweep-wake!
  "Handle one durable sweep wake, re-arming cadence before reconciliation.

  Scheduler delivery is at-least-once, so both the stable wake key and the run
  transitions are idempotent. Configuration changes serialize with the whole
  fire, so disable or removal cannot return before an entered sweep finishes.
  The handler never stops or restarts Mill."
  [{:keys [runtime payload]}]
  (let [interval-ms (:interval-ms payload)
        offset (or (:offset payload) 0)
        generation (:generation payload)
        config (sweep/config-lock runtime)]
    (require-valid! ::interval-ms interval-ms
                    "Harness reconciliation wake has an invalid interval")
    (require-valid! ::offset offset
                    "Harness reconciliation wake has an invalid offset")
    (require-valid! ::generation generation
                    "Harness reconciliation wake has an invalid generation")
    #_{:splint/disable [lint/locking-object]}
    (locking config
      (let [configured @config]
        (if (and (:enabled configured)
                 (= interval-ms (:interval-ms configured))
                 (= generation (:generation configured)))
          (let [next-offset (mod (+ offset sweep-limit) Long/MAX_VALUE)]
            (sweep/arm! runtime interval-ms next-offset generation)
            (reconcile! runtime {:source "scheduled"
                                 :limit sweep-limit
                                 :offset offset}))
          {:status :skipped :reason :sweep-disabled-or-reconfigured})))))

(lifecycle/defreconcile interactive-reconciliation-sweep
  "Keep the durable interactive orphan-reconciliation sweep scheduled."
  {:read-desired 'ct.spools.harnesses.reconciliation/desired-sweep
   :read-actual 'ct.spools.harnesses.reconciliation/actual-sweep
   :apply 'ct.spools.harnesses.reconciliation/apply-sweep!
   :on-removed 'ct.spools.harnesses.reconciliation/remove-sweep!
   :trigger-kinds #{}
   :after #{:harness-execution-runtime}})

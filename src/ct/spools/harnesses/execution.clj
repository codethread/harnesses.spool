(ns ct.spools.harnesses.execution
  "Asynchronous and interactive execution for provider-neutral harness runs."
  (:require [clojure.spec.alpha :as s]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.internal.launcher :as launcher]
            [ct.spools.harnesses.internal.process-custody :as custody]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.util.concurrent Executors ThreadFactory TimeUnit]))

(def ^:private state-version 3)
(def ^:private event-types #{:strand/added :strand/updated :batch/applied})

(declare schedule!
         inspect-owned!
         ^:private finish-process!
         ^:private state
         ^:private activate-state!
         ^:private deactivate-state!
         ^:private pending-headless
         ^:private claim!
         ^:private release!
         ^:private launch-headless!
         ^:private full-run
         ^:private resolved-definition
         ^:private prepare-launch
         ^:private callback)

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
  "Open harness execution resources and recover existing work."
  [{:keys [runtime]}]
  (require-valid! ::harness/runtime runtime
                  "harness execution open received an invalid runtime")
  (activate-state! runtime)
  (try
    (inspect-owned! runtime)
    {:opened :harness-execution
     :claimed (schedule! runtime)}
    (catch Throwable error
      (try
        ((:close-fn (deactivate-state! runtime)))
        (catch Throwable close-error
          (.addSuppressed error close-error)))
      (throw error))))

(defn close-execution!
  "Stop harness execution resources."
  [{:keys [runtime]}]
  ((:close-fn (deactivate-state! runtime)))
  {:closed :harness-execution})

(defn schedule!
  "Claim and asynchronously launch every ready pending headless run."
  [rt]
  (let [claimed (filterv #(claim! rt (:id %)) (pending-headless rt))
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
      (launcher/write! rt run (:argv launch-spec)))
    (catch Exception e
      (harness/finish! rt (:id run) {:status :failed
                                     :error (str (ex-message e)
                                                 (when-let [data (ex-data e)]
                                                   (str " " (pr-str data))))})
      (throw e))))

(defn mark-interactive-running!
  "Mark an interactive harness run as started."
  [rt id]
  (let [run (full-run rt id)]
    (when-not (= "interactive" (attr-get run :harness/mode))
      (fail! "_started applies only to interactive harness runs" {:id id}))
    (harness/mark-running! rt id)))

(defn finish-interactive!
  "Finish an interactive run through its provider callback."
  [rt id exit-code]
  (let [run (full-run rt id)]
    (when-not (= "interactive" (attr-get run :harness/mode))
      (fail! "_finished applies only to interactive harness runs" {:id id}))
    (try
      (let [definition (resolved-definition rt run)
            outcome ((callback (:finish definition))
                     rt definition run
                     {:exit-code exit-code :stdout nil :stderr nil})]
        (harness/finish! rt id outcome))
      (catch Exception e
        (harness/finish! rt id {:status :failed
                                :exit-code exit-code
                                :error (str (ex-message e)
                                            (when-let [data (ex-data e)]
                                              (str " " (pr-str data))))})))))

(defn launch-in-flight?
  "Return whether an active run still has an in-process launch claim."
  [rt run]
  (and (= "pending" (attr-get run :harness/process-handle))
       (contains? @(:in-flight (state rt)) (:id run))))

(defn inspect-owned!
  "Inspect and advance active headless runs backed by Mill process custody."
  [rt]
  (let [runs (filter #(and (= "true" (attr-get % :harness/run))
                           (= "running" (attr-get % :harness/phase))
                           (= "headless" (attr-get % :harness/mode))
                           ;; The launching worker owns the pending marker
                           ;; until Mill has returned a listable record. Only a
                           ;; replacement with no in-flight claim may recover
                           ;; it from owner/key custody listing.
                           (not (launch-in-flight? rt %)))
                     (weaver/list rt
                                  [:and [:= :state "active"]
                                   [:= [:attr "harness/run"] "true"]
                                   [:= [:attr "harness/phase"] "running"]]
                                  {}))]
    (when (seq runs)
      (let [records (custody/list-owned rt)
            transition-errors (atom [])]
        (doseq [run runs]
          (try
            (let [record (custody/record-for "harness" run records)
                  durable (custody/durable-attributes "harness"
                                                      (:id run)
                                                      (attr-get run :harness/attempt)
                                                      record)]
              ;; `pending` is the durable launch claim, never a relaunch signal.
              ;; Bind the one owner/key-matched opaque handle before continuing
              ;; to terminal observation or scheduling another inspection.
              (when (= "pending" (attr-get run :harness/process-handle))
                (weaver/update! rt (:id run) {:attributes durable}))
              (if (= :terminal (:phase record))
                (finish-process! rt run (resolved-definition rt run) record)
                (.schedule ^java.util.concurrent.ScheduledExecutorService
                 (:scheduler (state rt))
                           ^Runnable #(inspect-owned! rt)
                           100 TimeUnit/MILLISECONDS)))
            (catch Throwable error
              (let [id (:id run)
                    message (str "process custody reconciliation failed: "
                                 (ex-message error) " " (pr-str (ex-data error)))
                    record (some #(when (= (:key %) (attr-get run :harness/process-key)) %)
                                 records)
                    transition-error (try
                                       (harness/finish! rt id
                                                        {:status :failed
                                                         :error message})
                                       nil
                                       (catch Throwable transition-error
                                         transition-error))]
                (release! rt id)
                (when transition-error
                  ;; Retry only when the owner is still running and the custody
                  ;; fact is still nonterminal. A committed failed owner has no
                  ;; inspection work left to schedule.
                  (when (and record
                             (not= :terminal (:phase record))
                             (= "running"
                                (attr-get (full-run rt id) :harness/phase)))
                    (.schedule ^java.util.concurrent.ScheduledExecutorService
                     (:scheduler (state rt))
                               ^Runnable #(inspect-owned! rt)
                               100 TimeUnit/MILLISECONDS))
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
                                                                     (daemon-thread-factory))]
    {:in-flight (atom #{})
     :executor executor
     :scheduler scheduler
     :close-fn (fn []
                 (.shutdownNow executor)
                 (.shutdownNow scheduler)
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
    (when-not (and opened (compare-and-set! active opened nil))
      (fail! "Harness execution resources are not open" {}))
    opened))

(defn- callback [symbol]
  (or (requiring-resolve symbol)
      (fail! "Harness callback cannot be resolved" {:callback symbol})))

(defn- run? [run]
  (= "true" (attr-get run :harness/run)))

(defn- pending-headless [rt]
  (filterv #(and (run? %)
                 (= "pending" (attr-get % :harness/phase))
                 (= "headless" (attr-get % :harness/mode)))
           (weaver/ready rt)))

(defn- claim! [rt id]
  (let [claimed? (atom false)]
    (swap! (:in-flight (state rt))
           (fn [ids]
             (if (contains? ids id)
               ids
               (do (reset! claimed? true) (conj ids id)))))
    @claimed?))

(defn- release! [rt id]
  (swap! (:in-flight (state rt)) disj id))

(defn- full-run [rt id]
  (or (weaver/show rt id) (fail! "Harness run not found" {:id id})))

(defn- resolved-definition [rt run]
  (harness/concrete-harness rt (attr-get run :harness/harness)))

(defn- prepare-launch [rt definition run]
  (require-valid!
   ::harness/launch-spec
   ((callback (:prepare definition)) rt definition run)
   "Harness prepare must return a valid launch specification"))

(defn- process-spec [run {:keys [argv stdin]}]
  {:argv argv
   :cwd (attr-get run :harness/cwd)
   :env (cond-> {}
          (attr-get run :identity/id)
          (assoc "MILLSTRAND_AGENT_ID" (attr-get run :identity/id)))
   :stdin stdin})

(defn- finish-process! [rt run definition record]
  (weaver/update! rt (:id run)
                  {:attributes (custody/durable-attributes "harness"
                                                           (:id run)
                                                           (attr-get run :harness/attempt)
                                                           record)})
  (let [observed (custody/terminal-observed record)
        observed (if (some? (:exit-code observed))
                   observed
                   (assoc observed :exit-code 1
                          :stderr (or (:stderr observed)
                                      (custody/terminal-error observed)
                                      "Process custody terminal failure")))
        outcome ((callback (:finish definition)) rt definition run observed)]
    (harness/finish! rt (:id run) outcome)
    (custody/acknowledge! rt record)))

(defn- launch-headless!
  "Launch one already-claimed pending headless run."
  [rt id]
  (try
    (let [attempt (inc (or (attr-get (full-run rt id) :harness/attempt) 0))]
      (harness/mark-running! rt id)
      (let [run (full-run rt id)
            definition (resolved-definition rt run)
            launch-spec (prepare-launch rt definition run)
            _ (weaver/update! rt id
                              {:attributes
                               (merge
                                {:harness/attempt attempt
                                 :harness/started-at (str (java.time.Instant/now))}
                                (custody/durable-attributes
                                 "harness" id attempt
                                 {:handle "pending" :phase :starting}))})
            record (custody/launch! rt id attempt (process-spec run launch-spec))]
        (weaver/update! rt id
                        {:attributes
                         (custody/durable-attributes "harness" id attempt record)})
        (if (= :terminal (:phase record))
          (finish-process! rt (full-run rt id) definition record)
          (inspect-owned! rt))))
    (catch Exception e
      (if (= "failed" (attr-get (full-run rt id) :harness/phase))
        (throw e)
        (let [message (str (ex-message e)
                           (when-let [data (ex-data e)]
                             (str " " (pr-str data))))
              transition-error (try
                                 (harness/finish! rt id {:status :failed
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
                            transition-error))))))
    (finally
      (release! rt id)
      (inspect-owned! rt)
      (schedule! rt))))

(lifecycle/defresource harness-execution-runtime
  "Own asynchronous and interactive harness execution resources."
  {:open 'ct.spools.harnesses.execution/open-execution!
   :close 'ct.spools.harnesses.execution/close-execution!
   :after #{:claude-harness-runtime
            :codex-harness-runtime
            :cursor-harness-runtime
            :pi-harness-runtime}})

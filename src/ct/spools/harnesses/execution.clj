(ns ct.spools.harnesses.execution
  "Asynchronous and interactive execution for provider-neutral harness runs."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.internal.execution-custody :as execution-custody]
            [ct.spools.harnesses.internal.execution-headless :as execution-headless]
            [ct.spools.harnesses.internal.execution-state :as execution-state]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.launcher :as launcher]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.publication :as publication]
            [ct.spools.harnesses.internal.process-custody :as custody]
            [ct.spools.harnesses.reconciliation :as reconciliation]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]))

#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private state-version execution-state/state-version)
(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned})

(defn ^:dynamic ^:private guidance-deadline-hook!
  [_event]
  nil)

(def ^:dynamic ^:private *launch-state* nil)

(declare schedule! inspect-owned! launch-in-flight?
         ^:private state ^:private activate-state! ^:private deactivate-state!
         ^:private ready-headless ^:private claim! ^:private launch-headless!
         ^:private full-run ^:private resolved-definition
         ^:private prepare-launch ^:private process-spec
         ^:private schedule-guidance-deadline!
         ^:private recover-guidance-deadlines!)

(defn- callback [symbol]
  (or (requiring-resolve symbol)
      (fail! "Harness callback cannot be resolved" {:callback symbol})))

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
      (publication/recover-all! runtime)
      (let [recovery (try
                       (inspect-owned! runtime opened)
                       nil
                       (catch Throwable error
                         (reset! (:deferred-recovery opened) error)
                         error))
            _ (recover-guidance-deadlines! runtime opened)]
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
  (let [opened (state rt)
        claimed (filterv #(claim! opened (:id %)) (ready-headless rt))
        executor (:executor opened)]
    (doseq [run claimed]
      (.execute executor
                ^Runnable
                (bound-fn []
                  (binding [*launch-state* opened]
                    (launch-headless! rt (:id run))))))
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
  "Start an interactive run and arm its native run correlation.

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
         (when (= "codex" (attr-get strand :harness/harness))
           (launcher/arm-native! rt strand))
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

(defn- state-holder [rt]
  (execution-state/state-holder rt))

(defn- state [rt]
  (execution-state/state rt))

(defn- activate-state! [rt]
  (execution-state/activate-state! rt))

(defn- deactivate-state! [rt]
  (execution-state/deactivate-state! rt))

(defn- active-opened? [rt opened]
  (execution-state/active-opened? rt opened))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- arm-guidance-deadline! [rt run opened]
  (execution-state/arm-guidance-deadline! rt run opened))

(defn- schedule-guidance-deadline!
  ([rt run] (execution-state/schedule-guidance-deadline! rt run))
  ([rt opened run]
   (execution-state/schedule-guidance-deadline! rt opened run)))

(defn- recover-guidance-deadlines! [rt opened]
  (execution-state/recover-guidance-deadlines! rt opened))

(defn- schedule-inspection!
  ([rt] (execution-state/schedule-inspection! rt))
  ([rt opened] (execution-state/schedule-inspection! rt opened)))

(defn- full-run [rt id]
  (execution-headless/full-run rt id))

(defn- resolved-definition [rt run]
  (execution-headless/resolved-definition rt run))

(defn- prepare-launch [rt definition run]
  (execution-headless/prepare-launch rt definition run))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- process-spec [rt run launch-spec]
  (execution-headless/process-spec rt run launch-spec))

(defn- release-opened! [opened id]
  (execution-headless/release-opened! opened id))

(defn- headless-callbacks []
  {:full-run full-run
   :inspect-owned! inspect-owned!
   :release-opened! release-opened!
   :schedule! schedule!
   :state state
   :state-holder state-holder})

(defn- finish-process! [rt run definition record]
  (execution-headless/finish-process!
   (headless-callbacks) rt run definition record))

(defn- enforce-stop! [rt run record]
  (execution-headless/enforce-stop! rt run record))

(defn- custody-callbacks []
  {:active-opened? active-opened?
   :deadline-hook! guidance-deadline-hook!
   :enforce-stop! enforce-stop!
   :finish-process! finish-process!
   :full-run full-run
   :release-opened! release-opened!
   :resolved-definition resolved-definition
   :schedule-inspection! schedule-inspection!
   :state state})

(defn launch-in-flight?
  "Return whether this worker owns an unfinished launch for `run`."
  [rt run]
  (execution-custody/launch-in-flight? (custody-callbacks) rt run))

(defn inspect-owned!
  "Inspect and advance headless runs backed by Mill process custody."
  ([rt] (execution-custody/inspect-owned! (custody-callbacks) rt))
  ([rt opened]
   (execution-custody/inspect-owned! (custody-callbacks) rt opened)))

(defn- ready-headless [rt]
  (execution-headless/ready-headless rt))

(defn- claim! [opened id]
  (execution-headless/claim! opened id))

(defn- launch-headless! [rt id]
  (execution-headless/launch-headless!
   (headless-callbacks) *launch-state* rt id))

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

(lifecycle/defresource harness-execution-runtime
  "Own asynchronous and interactive harness execution resources."
  {:open 'ct.spools.harnesses.execution/open-execution!
   :close 'ct.spools.harnesses.execution/close-execution!
   :after #{:assignment-runtime
            :claude-harness-runtime
            :codex-harness-runtime
            :cursor-harness-runtime
            :pi-harness-runtime}})

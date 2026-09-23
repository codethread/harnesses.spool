(ns ct.spools.harnesses
  "Provider-neutral structure, registry, and lifecycle for harness runs."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.attribution :as attribution]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-receipts :as guidance-receipts]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.publication :as publication]
            [ct.spools.harnesses.internal.managed-startup :as managed]
            [ct.spools.harnesses.internal.native-registration :as native-registration]
            [ct.spools.harnesses.native-session :as native-session]
            [ct.spools.harnesses.internal.run-continuation :as continuation]
            [ct.spools.harnesses.internal.run-creation :as creation]
            [ct.spools.harnesses.internal.run-settlement :as settlement]
            [ct.spools.harnesses.internal.runs :as runs]
            [ct.spools.harnesses.internal.specs]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.util UUID]))

(def set-flag! catalog/set-flag!)
(def unset-flag! catalog/unset-flag!)
(def flags catalog/flags)
(def flag catalog/flag)
(def register-harness! catalog/register-harness!)
(def unregister-harness! catalog/unregister-harness!)
(def register-alias! catalog/register-alias!)
(def unregister-alias! catalog/unregister-alias!)
(def availability catalog/availability)
(def resolve-harness catalog/resolve-harness)
(def concrete-harness catalog/concrete-harness)
(def harnesses catalog/harnesses)
(def open-harness-core! catalog/open-harness-core!)
(def close-harness-core! catalog/close-harness-core!)

(def guidance-bootstrap-schema
  "Schema identifier for managed guidance launcher metadata."
  guidance/guidance-bootstrap-schema)

(def guidance-bundle-schema
  "Schema identifier for frozen native managed guidance."
  guidance/guidance-bundle-schema)

(def ^:dynamic ^:private *guidance-context-template* nil)

(defn guidance-acknowledge!
  "Record an exact adapter-handoff receipt for the current native attempt."
  [rt receipt]
  (guidance-receipts/acknowledge! rt receipt))

(defn guidance-fail!
  "Record an exact adapter failure receipt for the current native attempt."
  [rt receipt]
  (guidance-receipts/fail! rt receipt))

(defn register-native-session!
  "Register an actual native session, optionally attaching its managed run.

  Codex callers fence the exact managed invocation with `:run-reference`
  `RUN_ID:INVOCATION` and supply the observed model. Pi callers correlate with
  `:run-id` against the pinned native session and may carry the native fork
  parent header. Direct registrations have no alias or process custody; an
  observed unavailable effort is persisted as `harness/observed-effort=unknown`,
  never a launch option."
  [rt request]
  (case (:harness request)
    "codex" (native-session/register!
             rt (dissoc request :run-id :parent-native-session-id))
    "pi" (native-registration/register! rt (dissoc request :run-reference))
    (fail! "Native startup requires a codex or pi harness"
           {:harness (:harness request)})))

(defn run
  "Return one harness run strand by id, failing when it is absent or foreign."
  [rt id]
  (require-valid! ::runtime rt "run requires a Weaver runtime")
  (require-valid! ::id id "run requires a run id")
  (publication/recover! rt id)
  (runs/require-run rt id))

(defn call-with-run-publication-lock
  "Call zero-argument thunk synchronously under this runtime's publication monitor.

  Serialize bounded caller checks and writes with Harnesses run creation and
  continuation publication using the same catalog monitor as create!/resume!.
  The monitor is reentrant on the calling thread: thunk may call this helper or
  Harnesses publication operations for the same runtime. Return thunk's value
  unchanged; propagate its exception, releasing the monitor on either exit.

  This is process-local serialization, not a database transaction, rollback, or
  protection against raw edits that bypass the monitor. Keep thunk bounded and
  synchronous; do not wait for another thread or external work that may need
  this monitor. Asynchronous work is not protected after thunk returns."
  [rt thunk]
  (require-valid! ::runtime rt "call-with-run-publication-lock requires a Weaver runtime")
  (require-valid! ifn? thunk "call-with-run-publication-lock requires a callable thunk")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (thunk)))

(defn create!
  "Create, publish, and return one ready harness-run strand.

  Resolution normally uses the live alias registry. A frozen resolution lets a
  native continuation reuse the exact provider its predecessor used.

  Publication orders identity, target, request, and published markers so no
  scheduler can observe a launchable half-run. A repeated request ID returns
  only an equivalent prior publication."
  [rt request]
  (require-valid! ::runtime rt "create! requires a Weaver runtime")
  (require-valid! ::create-request request "create! requires a valid run request")
  (let [request-id (:request-id request)
        fingerprint (creation/fingerprint request)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (or (runs/request-match rt request-id fingerprint)
          (->> (creation/prepare-publication
                rt request *guidance-context-template* fingerprint)
               (creation/commit-publication! rt))))))

(s/fdef create! :args (s/cat :runtime ::runtime :request ::create-request) :ret ::strand)

(def ^:private interactive-start-attribute-keys
  #{:harness/completion-owner-pid
    :harness/completion-owner-started-at
    :harness/completion-owner-host})

(def ^:private interactive-custody-attribute-keys
  #{:harness/completion-owner-pid
    :harness/completion-owner-started-at
    :harness/completion-owner-host
    :harness/completion-owner-invocation
    :harness/provider-pid
    :harness/provider-started-at
    :harness/provider-host
    :harness/provider-invocation})

(defn- retired-interactive-custody []
  (zipmap interactive-custody-attribute-keys (repeat nil)))

(defn begin-attempt!
  "Move a published ready run to running and mint its fencing token.

  Returns `{:strand … :invocation … :attempt …}`. The invocation is the only
  token that may later finish this execution, so a callback from a superseded
  attempt cannot terminate the current one. A run stopped before it launched is
  no longer ready and is therefore refused here.

  The optional third argument is the closed completion-owner identity captured
  for an interactive start. It commits with the attempt and is fenced by the
  same invocation. Starting an interactive retry retires every prior attempt's
  process evidence before recording the new callback contract and custody."
  ([rt id] (begin-attempt! rt id {}))
  ([rt id start-attributes]
   (require-valid! ::runtime rt "begin-attempt! requires a Weaver runtime")
   (require-valid! ::id id "begin-attempt! requires a run id")
   (require-valid! map? start-attributes
                   "begin-attempt! start attributes must be a map")
   (when-not (or (empty? start-attributes)
                 (and (= interactive-start-attribute-keys
                         (set (keys start-attributes)))
                      (pos-int? (:harness/completion-owner-pid start-attributes))
                      (not (str/blank?
                            (:harness/completion-owner-started-at
                             start-attributes)))
                      (not (str/blank?
                            (:harness/completion-owner-host start-attributes)))))
     (fail! "begin-attempt! received invalid interactive start attributes"
            {:attributes start-attributes}))
   #_{:clj-kondo/ignore [:locking-suspicious-lock]}
   #_{:splint/disable [lint/locking-object]}
   (locking (catalog/publication-lock rt)
     (let [run (runs/require-run rt id)
           _ (guidance/validate-representation! run)
           interactive? (= "interactive" (attr-get run :harness/mode))
           attempt (inc (or (attr-get run :harness/attempt) 0))
           invocation (str (UUID/randomUUID))]
       (when-not (life/accepted? run)
         (fail! "Harness run is not published and cannot start" {:id id}))
       (when (and (seq start-attributes)
                  (not= "interactive" (attr-get run :harness/mode)))
         (fail! "Completion-owner evidence applies only to interactive runs"
                {:id id :mode (attr-get run :harness/mode)}))
       (when-not (= "ready" (life/status run))
         (fail! "Harness run is not ready to start"
                {:id id :status (life/status run)
                 :substatus (life/substatus run)}))
       (let [guidance-patch
             (try
               (guidance/begin-attempt-patch rt run attempt invocation)
               (catch Throwable error
                 (when (guidance/native? run)
                   (weaver/update!
                    rt id
                    {:attributes
                     (guidance/preflight-failure-patch
                      run attempt invocation error)}))
                 (throw error)))
             attempt-started-at
             (or (some-> guidance-patch :harness/guidance-attempts
                         peek (get "started-at"))
                 (life/now))]
         (require-valid!
          ::started
          {:strand (guidance/validation-run
                    rt
                    (require-valid!
                     ::strand
                     (weaver/update!
                      rt id
                      {:attributes
                       (merge
                        (when interactive? (retired-interactive-custody))
                        {:harness/status "running"
                         :harness/substatus nil
                         :harness/settled "false"
                         :harness/settlement nil
                         :harness/attempt attempt
                         :harness/invocation invocation
                         :harness/started-at attempt-started-at}
                        guidance-patch
                        start-attributes
                        (when interactive?
                          {:harness/interactive-callback-contract
                           (if (seq start-attributes) "v2" "legacy")})
                        (when (seq start-attributes)
                          {:harness/completion-owner-invocation invocation}))})
                     "begin-attempt! produced an invalid run strand"))
           :invocation invocation
           :attempt attempt}
          "begin-attempt! produced an invalid start record"))))))

(s/fdef begin-attempt!
  :args (s/or :plain (s/cat :runtime ::runtime :id ::id)
              :interactive
              (s/cat :runtime ::runtime :id ::id :start-attributes map?))
  :ret ::started)

(defn finish!
  "Record and return a terminal provider-neutral outcome, fenced by invocation.

  `:invocation` names the execution the outcome belongs to. A callback naming a
  superseded active attempt, or arriving after the run is already terminal,
  changes nothing and returns the run as it stands. A supplied invocation after
  retry has retired the current token fails loudly. Neither stale case can
  attach native identity, finish newer work, or rewrite a settled result.
  Settlement evidence remains separate from identity attachment.

  Codex/Pi identity must already be registered by native startup. A
  hook-confirmed session survives an interactive finish that cannot observe
  provider stdout; clean completion plus that binding makes native resume
  usable. Completion never mints or attaches an identity."
  [rt id {:keys [invocation evidence] :as outcome}]
  (require-valid! ::runtime rt "finish! requires a Weaver runtime")
  (require-valid! ::id id "finish! requires a run id")
  (require-valid! ::outcome outcome "finish! requires a valid outcome")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)
          _ (when (= "external" (attr-get run :harness/ownership))
              (fail! "Harnesses does not own external session completion" {:id id}))
          current (life/invocation run)
          status (let [status (:status outcome)]
                   (if (keyword? status) status (keyword (str status))))
          _ (when (and invocation
                       (nil? current)
                       (not (life/terminal? run)))
              (fail! "Harness finish has a retired invocation token"
                     {:id id :actual invocation}))
          stale? (or (and invocation current (not= invocation current))
                     (life/terminal? run))]
      (if stale?
        run
        (let [_ (when (and (= "running" (life/status run))
                           (nil? invocation))
                  (fail! "Running harness finish requires its invocation token"
                         {:id id :invocation current}))
              guidance-completion
              (if (= "pi" (attr-get run :harness/harness))
                (native-registration/completion run (assoc outcome :status status))
                (guidance-receipts/completion run (assoc outcome :status status)))
              outcome (:outcome guidance-completion)
              native-failure? (and (= "codex" (attr-get run :harness/harness))
                                   (life/invocation run)
                                   (or (not= (life/invocation run)
                                             (attr-get run :harness/native-attachment-invocation))
                                       (and (:session-id outcome)
                                            (not= (:session-id outcome)
                                                  (attr-get run :harness/session-id)))))
              outcome (if native-failure?
                        (assoc outcome :status :failed :session-usable false
                               :error "Codex native startup missing or inconsistent with the current invocation")
                        outcome)
              status (:status outcome)
              exit-code (:exit-code outcome)
              result (:result outcome)
              session-id (:session-id outcome)
              error (:error outcome)
              session-usable (:session-usable outcome)
              guidance-evidence (:evidence guidance-completion)
              evidence (if guidance-evidence
                         (merge (or evidence {}) guidance-evidence)
                         evidence)
              _ (when-not (contains? #{"ready" "running"} (life/status run))
                  (fail! "Harness finish transition is invalid"
                         {:id id :status (life/status run) :outcome status}))
              _ (when (and (= :done status) (not= 0 exit-code))
                  (fail! "Successful harness outcome requires exit code zero"
                         {:id id :exit-code exit-code}))
              _ (when (and (= :done status)
                           (= "headless" (attr-get run :harness/mode))
                           (str/blank? result))
                  (fail! "Successful headless harness outcome requires a result"
                         {:id id}))
              attached? (= "true" (attr-get run :harness/native-attached))
              session-id (if attached?
                           (attr-get run :harness/session-id)
                           (or session-id (attr-get run :harness/session-id)))
              evidence (or evidence
                           (life/settlement-evidence {:exit-code exit-code}))
              _ (when (and (managed/managed-harness?
                            (attr-get run :harness/harness))
                           (true? session-usable)
                           (not attached?))
                  (fail! "Managed session evidence was not attached to the run"
                         {:id id :session-id session-id}))
              usable? (or (and attached? (true? session-usable))
                          (and attached? (= :done status) (zero? exit-code))
                          (and (not (managed/managed-harness?
                                     (attr-get run :harness/harness)))
                               (true? session-usable)))
              patch (life/terminal-patch run status
                                         (cond-> evidence native-failure?
                                                 (assoc :failure-class "bootstrap"))
                                         (and (not native-failure?) usable?))]
          (require-valid!
           ::strand
           (weaver/update!
            rt id
            {:state (if (= "stopped" (:harness/status patch)) "closed" "active")
             :attributes
             (merge patch
                    (:attributes guidance-completion)
                    {:harness/exit-code exit-code
                     :harness/result result
                     :harness/session-id session-id
                     :harness/finished-at (life/now)
                     :harness/error (when (= :failed status)
                                      (or error "Harness process failed"))})})
           "finish! produced an invalid run strand"))))))

(s/fdef finish! :args (s/cat :runtime ::runtime :id ::id :outcome ::outcome) :ret ::strand)

(defn stop!
  "Record durable, idempotent stop intent for exactly one run.

  Ready runs settle immediately and can never launch. Running runs retain their
  status until observed settlement proves the provider stopped. Stopping a run
  never mutates the work strand it serves."
  [rt id request]
  (require-valid! ::runtime rt "stop! requires a Weaver runtime")
  (require-valid! ::id id "stop! requires a run id")
  (require-valid! ::stop-request request "stop! requires a valid stop request")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)]
      (when (native-registration/external? run)
        (fail! "Harnesses does not own external session processes" {:id id}))
      (if-let [patch (life/stop-patch run (:reason request))]
        (let [updated
              (require-valid!
               ::strand
               (attribution/update-with-action!
                rt id
                {:state (if (= "stopped"
                               (:harness/status patch))
                          "closed"
                          (:state run))
                 :attributes patch}
                "stop requested" (:by-identity request)
                (cond-> {}
                  (:reason request)
                  (assoc :harness/action-reason (:reason request))))
               "stop! produced an invalid run strand")]
          updated)
        run))))

(s/fdef stop! :args (s/cat :runtime ::runtime :id ::id :request ::stop-request) :ret ::strand)

(defn settle-outcome!
  "Record provider outcome and settlement for an already terminal run."
  [rt id outcome evidence]
  (require-valid! ::runtime rt "settle-outcome! requires a Weaver runtime")
  (require-valid! ::id id "settle-outcome! requires a run id")
  (require-valid! ::outcome outcome "settle-outcome! requires a valid outcome")
  (require-valid! ::evidence evidence "settle-outcome! requires evidence")
  (settlement/settle-outcome! rt id outcome evidence))

(defn settle!
  "Record positive settlement evidence for a run that is already terminal.

  Settlement proves process absence, not successful execution, so an earlier
  failure retains its failed status."
  [rt id evidence]
  (require-valid! ::runtime rt "settle! requires a Weaver runtime")
  (require-valid! ::id id "settle! requires a run id")
  (require-valid! ::evidence evidence "settle! requires settlement evidence")
  (let [run (runs/require-run rt id)]
    (when-not (life/terminal? run)
      (fail! "Only a terminal harness run may be settled"
             {:id id :status (life/status run)}))
    (require-valid!
     ::strand
     (weaver/update!
      rt id
      {:attributes (cond-> {:harness/settled (if (:settled evidence)
                                               "true"
                                               "false")
                            :harness/settlement (:settlement evidence)}
                     (:gap evidence)
                     (assoc :harness/settlement-gap (:gap evidence)))})
     "settle! produced an invalid run strand")))

(s/fdef settle! :args (s/cat :runtime ::runtime :id ::id :evidence ::evidence) :ret ::strand)

(defn self-complete!
  "Record best-effort result text for an interactive run.

  This optional user-driven signal does not change the run lifecycle. When an
  actor is supplied, an immutable action note preserves its attribution."
  ([rt id result]
   (self-complete! rt id result nil))
  ([rt id result by-identity]
   (require-valid! ::runtime rt "self-complete! requires a Weaver runtime")
   (require-valid! ::id id "self-complete! requires a run id")
   (require-valid! string? result "self-complete! requires result text")
   (when by-identity
     (require-valid! ::by-identity by-identity
                     "self-complete! requires a valid actor identity"))
   (let [run (runs/require-run rt id)]
     (when-not (= "interactive" (attr-get run :harness/mode))
       (fail! "self-complete applies only to interactive runs" {:id id}))
     (let [updated
           (require-valid! ::strand
                           (attribution/update-with-action!
                            rt id {:attributes {:harness/result result}}
                            "self-completed" by-identity {})
                           "self-complete! produced an invalid run strand")]
       updated))))

(s/fdef self-complete!
  :args (s/or :plain
              (s/cat :runtime ::runtime :id ::id :result string?)
              :attributed
              (s/cat :runtime ::runtime :id ::id :result string?
                     :by-identity (s/nilable ::by-identity)))
  :ret ::strand)

(defn retry!
  "Reset one settled failed ad-hoc run with validated replacement options."
  [rt id request]
  (require-valid! ::runtime rt "retry! requires a Weaver runtime")
  (require-valid! ::id id "retry! requires a run id")
  (require-valid! ::retry-request request "retry! requires valid replacements")
  (continuation/retry! rt id request))

(s/fdef retry! :args (s/cat :runtime ::runtime :id ::id :request ::retry-request) :ret ::strand)

(defn resume-eligibility
  "Return positive evidence that a run may be continued natively."
  [rt id]
  (require-valid! ::runtime rt "resume-eligibility requires a Weaver runtime")
  (require-valid! ::id id "resume-eligibility requires a run id")
  (continuation/resume-eligibility rt id))

(s/fdef resume-eligibility
  :args (s/cat :runtime ::runtime :id ::id)
  :ret ::resume-eligibility)

(defn resolve-resume-run
  "Resolve the unique current continuation head selected by the caller."
  [rt selector]
  (require-valid! ::runtime rt "resolve-resume-run requires a Weaver runtime")
  (require-valid! ::resume-selector selector
                  "resolve-resume-run requires exactly one selector")
  (continuation/resolve-resume-run rt selector))

(s/fdef resolve-resume-run
  :args (s/cat :runtime ::runtime :selector ::resume-selector)
  :ret ::strand)

(defn resume!
  "Create a new run continuing one predecessor's exact native session."
  [rt id request]
  (require-valid! ::runtime rt "resume! requires a Weaver runtime")
  (require-valid! ::id id "resume! requires a predecessor run id")
  (require-valid! ::resume-request request
                  "resume! requires valid continuation options")
  (continuation/resume!
   rt id request
   (fn [runtime create-request guidance-template]
     (binding [*guidance-context-template* guidance-template]
       (create! runtime create-request)))))

(s/fdef resume! :args (s/cat :runtime ::runtime :id ::id :request ::resume-request) :ret ::strand)

(defn resume-selected!
  "Resume one CLI-selected lineage head with request replay convergence.

  For an existing request ID, validate against the original selector and
  predecessor before consulting the lineage's current head."
  [rt selector request]
  (continuation/resume-selected!
   rt selector request
   (fn [runtime create-request guidance-template]
     (binding [*guidance-context-template* guidance-template]
       (create! runtime create-request)))))

(s/fdef resume-selected!
  :args (s/cat :runtime ::runtime :selector ::resume-selector
               :request ::resume-request)
  :ret ::strand)

(defn migrate-runs!
  "Project every legacy phase-only run onto the public status contract.

  Migration is deliberate rather than lazy: a legacy row is rewritten once, so
  queries and reservations read one vocabulary. A legacy failure is migrated
  unsettled, because nothing ever observed its process stop. Legacy success
  does not become session-usable. Returns the IDs migrated."
  [rt]
  (require-valid! ::runtime rt "migrate-runs! requires a Weaver runtime")
  (let [legacy (weaver/list rt
                            [:and
                             [:= [:attr "harness/run"] "true"]
                             [:missing [:attr "harness/status"]]]
                            {})]
    (vec (for [run legacy
               :let [patch (life/migration-patch run)]
               :when patch]
           (do (weaver/update! rt (:id run) {:attributes patch})
               (:id run))))))

(s/fdef migrate-runs! :args (s/cat :runtime ::runtime) :ret (s/coll-of ::id :kind vector?))

(lifecycle/defresource harness-core-runtime
  "Own the provider-neutral harness registry for the module lifetime."
  {:open 'ct.spools.harnesses/open-harness-core!
   :close 'ct.spools.harnesses/close-harness-core!})

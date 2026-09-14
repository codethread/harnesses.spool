(ns ct.spools.harnesses
  "Provider-neutral structure, registry, and lifecycle for harness runs."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-receipts :as guidance-receipts]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-repair :as managed-repair]
            [ct.spools.harnesses.internal.managed-startup :as managed]
            [ct.spools.harnesses.internal.registry :as registry]
            [ct.spools.harnesses.internal.runs :as runs]
            [ct.spools.harnesses.internal.specs]
            [millhouse.spools.identity :as identity]
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

(def managed-bootstrap-schema
  "Schema identifier for managed launch bootstrap metadata."
  managed/managed-bootstrap-schema)

(def managed-context-schema
  "Schema identifier for context returned by managed native startup."
  managed/managed-context-schema)

(def guidance-bootstrap-schema
  "Schema identifier for managed guidance launcher metadata."
  guidance/guidance-bootstrap-schema)

(def guidance-bundle-schema
  "Schema identifier for frozen native managed guidance."
  guidance/guidance-bundle-schema)

(def ^:dynamic ^:private *guidance-context-template* nil)

(defn- require-guidance-lineage! [run transport]
  (when (and (= "native-v1" (guidance/parse-transport transport))
             (= "pi" (attr-get run :harness/harness))
             (managed/legacy-managed-run? run))
    (fail! "Pre-reservation Pi continuations require legacy guidance transport"
           {:run-id (:id run)
            :guidance-transport transport
            :remedy "Explicitly select --guidance-transport legacy."})))

(defn guidance-acknowledge!
  "Record an exact adapter-handoff receipt for the current native attempt."
  [rt receipt]
  (guidance-receipts/acknowledge! rt receipt))

(defn guidance-fail!
  "Record an exact adapter failure receipt for the current native attempt."
  [rt receipt]
  (guidance-receipts/fail! rt receipt))

(defn managed-bootstrap
  "Return prompt-free bootstrap metadata for a managed running invocation."
  [rt id]
  (require-valid! ::runtime rt "managed-bootstrap requires a Weaver runtime")
  (require-valid! ::id id "managed-bootstrap requires a run id")
  (managed/bootstrap rt (runs/require-run rt id)))

(defn managed-startup!
  "Attach a managed Codex/Pi run to its actual native root session.

  The request must carry the exact versioned bootstrap exported for the current
  launch plus explicit native harness, session, cwd, and root scope."
  [rt request]
  (managed/startup! rt request))

(defn repair-managed-startup!
  "Repair one explicitly identified completed legacy Codex binding."
  [rt request]
  (managed-repair/repair! rt request))

(defn run
  "Return one harness run strand by id, failing when it is absent or foreign."
  [rt id]
  (require-valid! ::runtime rt "run requires a Weaver runtime")
  (require-valid! ::id id "run requires a run id")
  (runs/require-run rt id))

(defn create!
  "Create, publish, and return one ready harness-run strand.

  Resolution normally goes through the live alias registry. A `:frozen`
  resolution bypasses it entirely and is how a native resume reuses the exact
  provider its predecessor ran under.

  Publication is ordered: the strand commits ready but unpublished, then gains
  its session identity, its `serves` target link, and its request binding, and
  only then becomes `published`. Nothing schedules or reconciles an unpublished
  run, so a worker that dies mid-create leaves no launchable half-run behind.

  A `:request-id` makes the call idempotent. Repeating it with an equivalent
  request returns the original run; repeating it with a different one fails and
  names the run already holding the key.

  `:literal-extra-argv` is the interactive CLI's raw provider tail. It follows
  caller overlay precedence but remains exempt from invocation templating."
  [rt {:keys [harness mode prompt cwd attributes title resumes after session-id
              append-system-prompt literal-extra-argv by-identity target
              root-targets context request-id logical-id frozen
              guidance-transport]
       :as request}]
  (require-valid! ::runtime rt "create! requires a Weaver runtime")
  (require-valid! ::create-request request "create! requires a valid run request")
  (let [fingerprint (life/fingerprint (dissoc request :request-id))]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (or
       (runs/request-match rt request-id fingerprint)
       (let [mode (registry/mode-keyword (or mode :headless))
             {:keys [alias harness definition generated env]}
             (if frozen
               (runs/frozen-resolution rt frozen concrete-harness)
               (resolve-harness rt harness))
             overrides (cond-> (registry/normalize-overlay attributes)
                         append-system-prompt
                         (update registry/appended-system-prompts-attribute
                                 (fnil conj []) append-system-prompt)
                         (some? literal-extra-argv)
                         (assoc :harness/extra-argv literal-extra-argv))
             effective (registry/merge-overlays generated overrides)
             effective (if *guidance-context-template*
                         (assoc effective
                                :harness/appended-system-prompts
                                (or (get *guidance-context-template*
                                         "appended-system-prompts")
                                    (get *guidance-context-template*
                                         :appended-system-prompts)))
                         effective)
             cwd (or cwd (System/getProperty "user.dir"))
             requested-session-id session-id
             session-id (or session-id (str (UUID/randomUUID)))
             _ (when resumes
                 (require-guidance-lineage!
                  (runs/require-run rt resumes)
                  (or guidance-transport "legacy")))
             guidance-selection
             (guidance/select!
              rt {:harness harness
                  :requested guidance-transport
                  :mode mode
                  :cwd cwd
                  :env env
                  :effective effective
                  :session-id session-id
                  :resumes resumes})]
         (when by-identity
           (identity/current rt by-identity))
         (when-not (contains? (:modes definition) mode)
           (fail! "Harness does not support requested mode"
                  {:harness harness :mode mode :modes (:modes definition)}))
         (when (and (= :headless mode) (str/blank? prompt))
           (fail! "Headless harness run requires a prompt" {:harness alias}))
         (when-let [writers (seq (runs/reserving-session-writers rt session-id))]
           (fail! "Native session already has an active managed writer"
                  {:session-id session-id :runs (mapv :id writers)}))
         (when target
           (when-let [serving (seq (runs/reserving-target-runs rt target))]
             (fail! "Target already has an active managed run"
                    {:target target :runs (mapv :id serving)})))
         (runs/commit-run!
          rt
          {:title (or title (registry/run-title alias mode prompt))
           :alias alias :harness harness :mode mode :definition definition
           :generated generated :env env :overrides overrides
           :effective effective :literal-extra-argv literal-extra-argv
           :cwd cwd :session-id session-id
           :requested-session-id requested-session-id
           :prompt prompt :resumes resumes :after after :target target
           :root-targets root-targets :context context :request-id request-id
           :fingerprint fingerprint
           :logical-id logical-id :by-identity by-identity
           :guidance-selection guidance-selection
           :guidance-context-template *guidance-context-template*}))))))

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
           interactive? (= "interactive" (attr-get run :harness/mode))
           attempt (inc (or (attr-get run :harness/attempt) 0))
           invocation (str (UUID/randomUUID))]
       (when-not (life/published? run)
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
                 (throw error)))]
         (guidance/carry-launch-plan
          guidance-patch
          (require-valid!
           ::started
           {:strand (require-valid!
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
                         :harness/started-at (life/now)}
                        guidance-patch
                        start-attributes
                        (when interactive?
                          {:harness/interactive-callback-contract
                           (if (seq start-attributes) "v2" "legacy")})
                        (when (seq start-attributes)
                          {:harness/completion-owner-invocation invocation}))})
                     "begin-attempt! produced an invalid run strand")
            :invocation invocation
            :attempt attempt}
           "begin-attempt! produced an invalid start record")))))))

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

  Positive Codex/Pi session evidence first attaches the reserved identity. A
  hook-confirmed session survives an interactive finish that cannot observe
  provider stdout; clean completion plus that binding makes native resume
  usable.

  A pre-reservation Codex/Pi run retains its historical identity binding and
  provider session evidence without claiming native startup attachment."
  [rt id {:keys [invocation evidence] :as outcome}]
  (require-valid! ::runtime rt "finish! requires a Weaver runtime")
  (require-valid! ::id id "finish! requires a run id")
  (require-valid! ::outcome outcome "finish! requires a valid outcome")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)
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
              (guidance-receipts/completion run (assoc outcome :status status))
              outcome (:outcome guidance-completion)
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
              guidance-failed? (some? (:attributes guidance-completion))
              _ (managed/require-legacy-positive-attempt!
                 run outcome)
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
              legacy-managed? (managed/legacy-managed-run? run)
              _ (when-not guidance-failed?
                  (managed/attach-outcome! rt run outcome))
              run (runs/require-run rt id)
              attached? (= "true" (attr-get run :harness/native-attached))
              session-id (if attached?
                           (attr-get run :harness/session-id)
                           (or session-id (attr-get run :harness/session-id)))
              evidence (or evidence
                           (life/settlement-evidence {:exit-code exit-code}))
              _ (when (and (managed/managed-harness?
                            (attr-get run :harness/harness))
                           (true? session-usable)
                           (not (or attached? legacy-managed?)))
                  (fail! "Managed session evidence was not attached to the run"
                         {:id id :session-id session-id}))
              usable? (or (and attached? (true? session-usable))
                          (and attached? (= :done status) (zero? exit-code))
                          (and legacy-managed? (true? session-usable))
                          (and (not (managed/managed-harness?
                                     (attr-get run :harness/harness)))
                               (true? session-usable)))
              patch (life/terminal-patch run status evidence usable?)]
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

  A ready run has no process, so it settles immediately as `stopped/requested`
  and can never launch. A running run keeps its `running` status: only observed
  settlement may move it, so a stop that has been requested but not confirmed
  never claims the provider has actually stopped. Repeat calls, and calls
  against an already terminal run, are no-ops.

  Stopping a run never touches whatever work strand it serves."
  [rt id request]
  (require-valid! ::runtime rt "stop! requires a Weaver runtime")
  (require-valid! ::id id "stop! requires a run id")
  (require-valid! ::stop-request request "stop! requires a valid stop request")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)]
      (if-let [patch (life/stop-patch run (:reason request))]
        (require-valid!
         ::strand
         (weaver/update! rt id
                         {:state (if (= "stopped" (:harness/status patch))
                                   "closed"
                                   (:state run))
                          :attributes patch})
         "stop! produced an invalid run strand")
        run))))

(s/fdef stop! :args (s/cat :runtime ::runtime :id ::id :request ::stop-request) :ret ::strand)

(defn settle-outcome!
  "Record provider outcome and settlement for an already terminal run.

  Custody evidence is persisted independently before optional
  reservation-backed Codex/Pi attachment. An attachment failure therefore
  cannot erase proof that the provider process settled. Existing hook-confirmed
  session evidence is never replaced by an unobserved interactive outcome.

  Positive legacy evidence is validated before the custody update so malformed
  callbacks write nothing. A fenced failed outcome with no usable session has
  no identity evidence to attach, so its custody settlement remains recordable
  even when the historical identity is damaged. Valid pre-reservation runs keep
  their historical representation without invented attachment evidence."
  [rt id outcome evidence]
  (require-valid! ::runtime rt "settle-outcome! requires a Weaver runtime")
  (require-valid! ::id id "settle-outcome! requires a run id")
  (require-valid! ::outcome outcome "settle-outcome! requires a valid outcome")
  (require-valid! ::evidence evidence "settle-outcome! requires evidence")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)
          invocation (:invocation outcome)]
      (when-not (life/terminal? run)
        (fail! "Only a terminal harness run may receive late outcome evidence"
               {:id id :status (life/status run)}))
      (managed/validate-legacy-outcome! rt run outcome)
      (when (and (attr-get run :harness/invocation)
                 (not= invocation (attr-get run :harness/invocation)))
        (fail! "Late harness outcome has a missing or stale invocation"
               {:id id
                :expected (attr-get run :harness/invocation)
                :actual invocation}))
      (let [managed? (managed/managed-harness?
                      (attr-get run :harness/harness))
            attached? (= "true" (attr-get run :harness/native-attached))
            session-id (if attached?
                         (attr-get run :harness/session-id)
                         (or (:session-id outcome)
                             (attr-get run :harness/session-id)))
            usable? (or (= "true" (attr-get run :harness/session-usable))
                        (and (or (not managed?) attached?)
                             (true? (:session-usable outcome))))
            settled (require-valid!
                     ::strand
                     (weaver/update!
                      rt id
                      {:attributes
                       (merge
                        {:harness/exit-code (:exit-code outcome)
                         :harness/result (:result outcome)
                         :harness/session-id session-id
                         :harness/session-usable (if usable? "true" "false")
                         :harness/error
                         (or (attr-get run :harness/error)
                             (when (= :failed (:status outcome))
                               (or (:error outcome)
                                   "Harness process failed")))
                         :harness/settled (if (:settled evidence) "true" "false")
                         :harness/settlement (:settlement evidence)}
                        (when-let [gap (:gap evidence)]
                          {:harness/settlement-gap gap}))})
                     "settle-outcome! produced an invalid run strand")
            bootstrap-failed?
            (and (guidance/native? run)
                 (= "bootstrap" (life/substatus run)))
            attached-result (when-not bootstrap-failed?
                              (managed/attach-outcome! rt settled outcome))]
        (if attached-result
          (require-valid!
           ::strand
           (weaver/update!
            rt id
            {:attributes
             {:harness/session-usable
              (if (true? (:session-usable outcome)) "true" "false")}})
           "settle-outcome! produced invalid attached session evidence")
          settled)))))

(defn settle!
  "Record positive settlement evidence for a run that is already terminal.

  Execution calls this when a terminal custody fact arrives after the outcome,
  which is the normal ordering for a stop. An earlier failure keeps its failed
  status: settling proves the process is gone, not that the run succeeded."
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
      {:attributes (cond-> {:harness/settled (if (:settled evidence) "true" "false")
                            :harness/settlement (:settlement evidence)}
                     (:gap evidence)
                     (assoc :harness/settlement-gap (:gap evidence)))})
     "settle! produced an invalid run strand")))

(s/fdef settle! :args (s/cat :runtime ::runtime :id ::id :evidence ::evidence) :ret ::strand)

(defn self-complete!
  "Record best-effort result text for an interactive run.

  This optional user-driven signal does not change the run lifecycle."
  [rt id result]
  (require-valid! ::runtime rt "self-complete! requires a Weaver runtime")
  (require-valid! ::id id "self-complete! requires a run id")
  (require-valid! string? result "self-complete! requires result text")
  (let [run (runs/require-run rt id)]
    (when-not (= "interactive" (attr-get run :harness/mode))
      (fail! "self-complete applies only to interactive runs" {:id id}))
    (require-valid! ::strand
                    (weaver/update! rt id {:attributes {:harness/result result}})
                    "self-complete! produced an invalid run strand")))

(s/fdef self-complete! :args (s/cat :runtime ::runtime :id ::id :result string?) :ret ::strand)

(defn- validate-native-retry-settings!
  [run request]
  (let [retained-harness (attr-get run :harness/harness)
        requested-harness (some-> (:harness request)
                                  (registry/name-string "Retry harness"))]
    (when (and requested-harness
               (not= retained-harness requested-harness))
      (fail! "Native resume retry cannot replace its frozen provider"
             {:id (:id run)
              :retained retained-harness
              :requested requested-harness}))
    (when (and (contains? request :cwd)
               (not= (:cwd request) (attr-get run :harness/cwd)))
      (fail! "Native resume retry cannot change frozen cwd"
             {:id (:id run)
              :retained (attr-get run :harness/cwd)
              :requested (:cwd request)}))
    (when (and (contains? request :attributes)
               (not= (registry/normalize-overlay (:attributes request))
                     (registry/normalize-overlay
                      (attr-get run :harness/overrides))))
      (fail! "Native resume retry cannot change frozen provider settings"
             {:id (:id run)}))))

(defn- native-retry-plan [rt run request]
  (validate-native-retry-settings! run request)
  (let [harness (attr-get run :harness/harness)]
    {:requested (attr-get run :harness/alias)
     :resolved (runs/frozen-resolution
                rt
                {:alias (attr-get run :harness/alias)
                 :harness harness
                 :generated (attr-get run :harness/generated)
                 :env (attr-get run :harness/env)}
                concrete-harness)
     :overrides (registry/normalize-overlay
                 (attr-get run :harness/overrides))
     :cwd (attr-get run :harness/cwd)}))

(defn- ordinary-retry-plan [rt run {:keys [harness cwd attributes]}]
  (let [requested (or harness (attr-get run :harness/alias))
        resolved (resolve-harness rt requested)
        old-overrides (registry/normalize-overlay
                       (attr-get run :harness/overrides))]
    {:requested requested
     :resolved resolved
     :overrides (reduce-kv
                 (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
                 old-overrides
                 (registry/normalize-overlay attributes))
     :cwd (or cwd (attr-get run :harness/cwd))}))

(defn retry!
  "Reconstruct and reset one failed ad-hoc run, applying replacement options.

  Request-bound assigned work cannot be retried in place: continue it with
  `resume!` or submit a new request. A fresh Codex/Pi retry reserves a fresh
  identity and refreshes invocation markers before becoming ready. Retrying a
  native-resume attempt keeps its attached native identity and session."
  [rt id request]
  (require-valid! ::runtime rt "retry! requires a Weaver runtime")
  (require-valid! ::id id "retry! requires a run id")
  (require-valid! ::retry-request request "retry! requires valid replacements")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (runs/require-run rt id)
          _ (when-not (= "failed" (life/status run))
              (fail! "Only a failed harness run may be retried"
                     {:id id :status (life/status run)}))
          _ (when-not (life/settled? run)
              (fail! "Only a settled failed harness run may be retried"
                     {:id id :settlement (attr-get run :harness/settlement)}))
          _ (when (attr-get run :harness/request-id)
              (fail! "A request-bound run cannot be retried in place"
                     {:id id :request-id (attr-get run :harness/request-id)}))
          _ (runs/require-continuation-head! rt id)
          old-concrete (attr-get run :harness/harness)
          resumed? (some? (attr-get run :harness/resumes))
          managed-native-resume?
          (and resumed? (managed/managed-harness? old-concrete))
          {:keys [requested resolved overrides cwd]}
          (if managed-native-resume?
            (native-retry-plan rt run request)
            (ordinary-retry-plan rt run request))
          concrete (:harness resolved)
          _ (when (and (managed/managed-harness? old-concrete)
                       (not (managed/managed-harness? concrete)))
              (fail! "Retry cannot move a managed identity to a maintenance provider"
                     {:id id :retained old-concrete :requested concrete}))
          generated (:generated resolved)
          inherited-transport (guidance/transport run)
          selected-transport
          (guidance/parse-transport (or (:guidance-transport request)
                                        inherited-transport))
          _ (require-guidance-lineage! run selected-transport)
          frozen-guidance-template
          (attr-get run :harness/guidance-context-template)
          _ (when (and (= "native-v1" selected-transport)
                       (nil? frozen-guidance-template))
              (fail! "Native retry requires a versioned frozen guidance template"
                     {:id id}))
          effective (registry/merge-overlays generated overrides)
          effective (if (and resumed? frozen-guidance-template)
                      (assoc effective :harness/appended-system-prompts
                             (or (get frozen-guidance-template
                                      "appended-system-prompts")
                                 (get frozen-guidance-template
                                      :appended-system-prompts)))
                      effective)
          session-id (if resumed?
                       (attr-get run :harness/session-id)
                       (str (UUID/randomUUID)))
          target (attr-get run :harness/target)
          target-writers (when target
                           (remove #(= id (:id %))
                                   (runs/reserving-target-runs rt target)))
          session-writers (when resumed?
                            (remove #(= id (:id %))
                                    (runs/reserving-session-writers
                                     rt session-id)))
          guidance-selection
          (guidance/select!
           rt {:harness concrete
               :requested selected-transport
               :mode (keyword (attr-get run :harness/mode))
               :cwd cwd
               :env (:env resolved)
               :effective effective
               :session-id session-id
               :resumes (attr-get run :harness/resumes)})]
      (when (seq target-writers)
        (fail! "Retry target already has an active managed run"
               {:id id :target target :runs (mapv :id target-writers)}))
      (when (seq session-writers)
        (fail! "Retry native session already has an active managed writer"
               {:id id :session-id session-id
                :runs (mapv :id session-writers)}))
      (concrete-harness rt concrete)
      (let [identity-binding (managed/retry-identity!
                              rt run concrete session-id effective)
            identity-id (or (:identity identity-binding)
                            (attr-get run :identity/id))
            identity-prompt (or (:prompt identity-binding)
                                (attr-get run :identity/prompt))
            guidance-patch
            (guidance/publication-patch
             rt id identity-id identity-prompt
             (:harness/appended-system-prompts effective)
             guidance-selection
             (when resumed? frozen-guidance-template)
             (attr-get run :harness/guidance-attempts))]
        (require-valid!
         ::strand
         (weaver/update!
          rt id
          {:attributes
           (merge
            (runs/retry-attribute-patch
             run {:requested requested
                  :concrete concrete
                  :env (:env resolved)
                  :generated generated
                  :overrides overrides
                  :effective effective
                  :cwd cwd
                  :session-id session-id
                  :identity-binding identity-binding})
            guidance-patch)})
         "retry! produced an invalid run strand")))))

(s/fdef retry! :args (s/cat :runtime ::runtime :id ::id :request ::retry-request) :ret ::strand)

(defn resume-eligibility
  "Return whether `run` may be continued natively, and why.

  Eligibility is positive evidence only: the run must be terminal, provably
  settled, hold a native session the provider has verified as usable, and have
  no other run currently reserving that session. A pre-reservation Pi run is
  eligible only while its exact historical identity and provenance remain
  valid. Legacy Codex mismatch recovery still requires explicit repair."
  [rt id]
  (require-valid! ::runtime rt "resume-eligibility requires a Weaver runtime")
  (require-valid! ::id id "resume-eligibility requires a run id")
  (let [run (runs/require-run rt id)
        session-id (attr-get run :harness/session-id)
        writers (if (str/blank? session-id)
                  []
                  (remove #(= id (:id %))
                          (runs/reserving-session-writers rt session-id)))
        legacy? (managed/legacy-managed-run? run)
        result (cond
                 (and legacy? (= "pi" (attr-get run :harness/harness)))
                 (do
                   (managed/require-legacy-pi-continuation! rt run)
                   (life/resume-eligibility run (count writers)))

                 legacy?
                 {:eligible? false
                  :reason (str "legacy Codex run has no verified native binding; "
                               "native resume requires explicit repair")}

                 :else
                 (life/resume-eligibility run (count writers)))]
    (require-valid! ::resume-eligibility result
                    "resume-eligibility produced an invalid result")))

(s/fdef resume-eligibility
  :args (s/cat :runtime ::runtime :id ::id)
  :ret ::resume-eligibility)

(defn resolve-resume-run
  "Resolve one resumable predecessor from exactly one selector.

  `selector` contains one of `:run-id`, `:session-id`, `:identity`, or
  `:logical-id`. A run ID resolves exactly but rejects a superseded
  predecessor. The other selectors resolve the latest accepted *head* of the
  lineage. Every published child is considered, so a still-running continuation
  cannot fall back to a stale ancestor. Missing, conflicting, and unmatched
  selectors fail loudly."
  [rt selector]
  (require-valid! ::runtime rt "resolve-resume-run requires a Weaver runtime")
  (require-valid! ::resume-selector selector
                  "resolve-resume-run requires exactly one selector")
  (if-let [run-id (:run-id selector)]
    (do
      (runs/require-continuation-head! rt run-id)
      (runs/require-run rt run-id))
    (let [[attribute value] (cond
                              (:session-id selector)
                              [:harness/session-id (:session-id selector)]

                              (:identity selector)
                              [:identity/id (:identity selector)]

                              :else
                              [:harness/logical-id (:logical-id selector)])]
      (runs/resolve-lineage-head rt attribute value selector))))

(s/fdef resolve-resume-run
  :args (s/cat :runtime ::runtime :selector ::resume-selector)
  :ret ::strand)

(defn- validate-resume-settings!
  [run request]
  (let [retained-mode (attr-get run :harness/mode)
        requested-mode (:mode request)
        requested-mode (some-> requested-mode name)]
    (when (and requested-mode (not= retained-mode requested-mode))
      (fail! "Native resume cannot change the run mode"
             {:id (:id run) :retained retained-mode :requested requested-mode}))
    (when (and (contains? request :cwd)
               (not= (:cwd request) (attr-get run :harness/cwd)))
      (fail! "Native resume cannot change cwd"
             {:id (:id run)
              :retained (attr-get run :harness/cwd)
              :requested (:cwd request)}))
    (when (and (contains? request :target)
               (not= (:target request) (attr-get run :harness/target)))
      (fail! "Native resume cannot change target"
             {:id (:id run)
              :retained (attr-get run :harness/target)
              :requested (:target request)}))
    (when (and (contains? request :context)
               (not= (:context request) (attr-get run :harness/context)))
      (fail! "Native resume cannot change frozen context"
             {:id (:id run)}))
    (when (and (contains? request :attributes)
               (not= (registry/normalize-overlay (:attributes request))
                     (registry/normalize-overlay
                      (attr-get run :harness/overrides))))
      (fail! "Native resume cannot change provider settings"
             {:id (:id run)}))))

(defn resume!
  "Create a new run continuing one predecessor's exact native session.

  The continuation keeps the predecessor's logical identity, concrete provider,
  native session, cwd, provider settings, assignment guidance, and target.
  Caller prompts are the only new user content; frozen settings and context
  cannot be replaced. It is created from that frozen resolution rather than by
  resolving the alias again.

  A repeated `:request-id` returns the original continuation even while that
  child is still active. Ineligible predecessors fail loudly and are never
  quietly restarted fresh."
  [rt id {:keys [prompt cwd attributes mode title by-identity request-id
                 guidance-transport]
          :as request}]
  (require-valid! ::runtime rt "resume! requires a Weaver runtime")
  (require-valid! ::id id "resume! requires a predecessor run id")
  (require-valid! ::resume-request request "resume! requires valid continuation options")
  (let [run (runs/require-run rt id)
        _ (validate-resume-settings! run request)
        target (attr-get run :harness/target)
        root-targets (attr-get run :harness/root-targets)
        context (attr-get run :harness/context)
        context (cond-> context
                  (and (map? context)
                       (or (contains? context "assignment/run-id")
                           (contains? context :assignment/run-id)))
                  (assoc "assignment/run-id" "{{RUN_ID}}"))
        inherited-transport (guidance/transport run)
        selected-transport
        (guidance/parse-transport (or guidance-transport inherited-transport))
        frozen-guidance-template
        (attr-get run :harness/guidance-context-template)
        _ (require-guidance-lineage! run selected-transport)
        _ (when (and (= "native-v1" selected-transport)
                     (nil? frozen-guidance-template))
            (fail! "Native resume requires a versioned frozen guidance template"
                   {:id id}))
        retained (registry/normalize-overlay (attr-get run :harness/overrides))
        retained (if (and (= "legacy" selected-transport)
                          (get retained :harness/appended-system-prompts))
                   (update retained :harness/appended-system-prompts
                           #(mapv (fn [prompt]
                                    (str/replace prompt id "{{RUN_ID}}"))
                                  %))
                   retained)
        replacements (registry/normalize-overlay attributes)
        overrides (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
                             retained replacements)
        literal-extra-argv
        (when (= "true" (attr-get run :harness.internal/literal-extra-argv))
          (attr-get run :harness/extra-argv))
        generated (registry/normalize-overlay (attr-get run :harness/generated))
        create-request (cond-> {:harness (attr-get run :harness/harness)
                                :frozen {:alias (attr-get run :harness/alias)
                                         :harness (attr-get run :harness/harness)
                                         :generated generated
                                         :env (or (attr-get run :harness/env) {})}
                                :mode (or mode (attr-get run :harness/mode))
                                :cwd (or cwd (attr-get run :harness/cwd))
                                :attributes overrides
                                :guidance-transport selected-transport
                                :resumes id
                                :logical-id (life/logical-id run)
                                :session-id (attr-get run :harness/session-id)}
                         (some? prompt) (assoc :prompt prompt)
                         (some? title) (assoc :title title)
                         (some? literal-extra-argv)
                         (assoc :literal-extra-argv literal-extra-argv)
                         (some? by-identity) (assoc :by-identity by-identity)
                         (some? target) (assoc :target target)
                         (some? root-targets) (assoc :root-targets root-targets)
                         (some? context) (assoc :context context)
                         (some? request-id) (assoc :request-id request-id))
        fingerprint (life/fingerprint (dissoc create-request :request-id))]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (or (runs/request-match rt request-id fingerprint)
          (let [_ (runs/require-continuation-head! rt id)
                {:keys [eligible? reason]} (resume-eligibility rt id)]
            (when-not eligible?
              (fail! "Harness run cannot be resumed natively"
                     {:id id :reason reason :status (life/status run)}))
            (binding [*guidance-context-template*
                      (when (= "native-v1" selected-transport)
                        frozen-guidance-template)]
              (create! rt create-request)))))))

(s/fdef resume! :args (s/cat :runtime ::runtime :id ::id :request ::resume-request) :ret ::strand)

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

(ns ct.spools.harnesses.internal.run-continuation
  "Retry and native-session continuation orchestration."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.attribution :as attribution]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-startup :as managed]
            [ct.spools.harnesses.internal.registry :as registry]
            [ct.spools.harnesses.internal.runs :as runs]
            [millstrand.api.spool.alpha :refer [attr-get fail!]])
  (:import [java.util UUID]))

(defn- require-valid! [spec value message]
  (if (s/valid? spec value)
    value
    (fail! message {:explain (s/explain-data spec value)})))

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
                catalog/concrete-harness)
     :overrides (registry/normalize-overlay
                 (attr-get run :harness/overrides))
     :cwd (attr-get run :harness/cwd)}))

(defn- ordinary-retry-plan [rt run {:keys [harness cwd attributes]}]
  (let [requested (or harness (attr-get run :harness/alias))
        resolved (catalog/resolve-harness rt requested)
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
  `resume!` or submit a new request. A fresh Codex/Pi retry defers identity to
  native startup. Retrying a native-resume attempt keeps its native session
  and requires registration for the new invocation."
  [rt id {:keys [by-identity] :as request}]
  (require-valid! :ct.spools.harnesses/runtime rt "retry! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/id id "retry! requires a run id")
  (require-valid! :ct.spools.harnesses/retry-request request "retry! requires valid replacements")
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
          retired-guidance-attempts (guidance/retire-current-attempt run)
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
               :requested (:guidance-transport request)
               :inherited inherited-transport
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
      (catalog/concrete-harness rt concrete)
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
             retired-guidance-attempts)
            attributes
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
             guidance-patch)
            prospective-attributes
            (reduce-kv (fn [stored key value]
                         (if (nil? value)
                           (dissoc stored key)
                           (assoc stored key value)))
                       (:attributes run)
                       attributes)
            _ (guidance/validate-representation!
               (assoc run :attributes prospective-attributes))
            updated
            (require-valid!
             :ct.spools.harnesses/strand
             (attribution/update-with-action!
              rt id {:attributes attributes} "retried" by-identity {})
             "retry! produced an invalid run strand")]
        updated))))

(s/fdef retry! :args (s/cat :runtime :ct.spools.harnesses/runtime :id :ct.spools.harnesses/id :request :ct.spools.harnesses/retry-request) :ret :ct.spools.harnesses/strand)

(defn resume-eligibility
  "Return whether `run` may be continued natively, and why.

  Eligibility is positive evidence only: the run must be terminal, provably
  settled, hold a native session the provider has verified as usable, and have
  no other run currently reserving that session."
  [rt id]
  (require-valid! :ct.spools.harnesses/runtime rt "resume-eligibility requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/id id "resume-eligibility requires a run id")
  (let [run (runs/require-run rt id)
        session-id (attr-get run :harness/session-id)
        writers (if (str/blank? session-id)
                  []
                  (remove #(= id (:id %))
                          (runs/reserving-session-writers rt session-id)))
        result (cond
                 (not (life/accepted? run))
                 {:eligible? false :reason "run publication was not accepted"}

                 (and (= "codex" (attr-get run :harness/harness))
                      (not= "native-startup" (attr-get run :harness/native-attachment-source)))
                 {:eligible? false :reason "Codex native startup has not registered this run"}

                 :else
                 (life/resume-eligibility run (count writers)))]
    (require-valid! :ct.spools.harnesses/resume-eligibility result
                    "resume-eligibility produced an invalid result")))

(s/fdef resume-eligibility
  :args (s/cat :runtime :ct.spools.harnesses/runtime :id :ct.spools.harnesses/id)
  :ret :ct.spools.harnesses/resume-eligibility)

(defn resolve-resume-run
  "Resolve one resumable predecessor from exactly one selector.

  `selector` contains one of `:run-id`, `:session-id`, `:identity`, or
  `:logical-id`. A run ID resolves exactly but rejects a superseded
  predecessor. The other selectors resolve the latest accepted *head* of the
  lineage. Every published child is considered, so a still-running continuation
  cannot fall back to a stale ancestor. Missing, conflicting, and unmatched
  selectors fail loudly."
  [rt selector]
  (require-valid! :ct.spools.harnesses/runtime rt "resolve-resume-run requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/resume-selector selector
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
  :args (s/cat :runtime :ct.spools.harnesses/runtime :selector :ct.spools.harnesses/resume-selector)
  :ret :ct.spools.harnesses/strand)

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
                 guidance-transport resume-selector-intent]
          :as request} create!]
  (require-valid! :ct.spools.harnesses/runtime rt "resume! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/id id "resume! requires a predecessor run id")
  (require-valid! :ct.spools.harnesses/resume-request request "resume! requires valid continuation options")
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
                                :guidance-transport (if (managed/managed-harness?
                                                         (attr-get run :harness/harness))
                                                      guidance-transport selected-transport)
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
                         (some? request-id) (assoc :request-id request-id)
                         (some? resume-selector-intent)
                         (assoc :resume-selector-intent
                                resume-selector-intent))
        create-request (cond-> create-request
                         (nil? (:guidance-transport create-request))
                         (dissoc :guidance-transport))
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
            (create! rt create-request
                     (when (= "native-v1" selected-transport)
                       frozen-guidance-template)))))))

(s/fdef resume!
  :args (s/cat :runtime :ct.spools.harnesses/runtime
               :id :ct.spools.harnesses/id
               :request :ct.spools.harnesses/resume-request
               :create! ifn?)
  :ret :ct.spools.harnesses/strand)

(defn resume-selected!
  "Resume the predecessor selected by one immutable CLI selector.

  An existing request key recovers its stored predecessor before selector
  resolution, allowing an exact replay to converge after the lineage advances.
  The selector remains part of the request fingerprint, so a different selector
  cannot reuse the key even when it once named the same predecessor."
  [rt selector {:keys [request-id] :as request} create!]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "resume-selected! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/resume-selector selector
                  "resume-selected! requires exactly one selector")
  (require-valid! :ct.spools.harnesses/resume-request request
                  "resume-selected! requires valid continuation options")
  (let [existing (runs/request-holder rt request-id)
        predecessor-id
        (if existing
          (or (attr-get existing :harness/resumes)
              (fail! "Request id is already held by a different harness request"
                     {:request-id request-id :run (:id existing)}))
          (:id (resolve-resume-run rt selector)))]
    (resume! rt predecessor-id
             (assoc request :resume-selector-intent selector)
             create!)))

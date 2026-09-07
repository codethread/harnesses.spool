(ns ct.spools.harnesses.assignment
  "Assign a tracked agent run to a work target.

  Assignment is an agent operation: it accepts a published ready run that
  `serves` the target, freezes named policy prose into durable context, and
  writes a work prompt. It does not claim the target, create a worktree, add a
  `depends-on` from the run to the card, or close anything when the process
  exits.

  Launch eligibility is separate from acceptance. A blocked target may be
  accepted; scheduling must consult `target-ready?` before starting the
  worker."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.internal.assignment :as internal]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(def ^:private state-version
  "Bump when `new-state` gains or drops keys."
  1)

(def ^:private default-policy-name "stop-on-complete")

(defn- new-state
  []
  {:policies (atom {})})

(defn- state
  [rt]
  (runtime/spool-state rt ::assignment {:version state-version} new-state))

(defn- policies
  [rt]
  (:policies (state rt)))

(defn- policy-key
  [policy-name]
  (keyword (if (keyword? policy-name)
             (name policy-name)
             (str policy-name))))

(defn- json-value?
  [value]
  (cond
    (or (nil? value) (string? value) (number? value) (boolean? value)) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-value? (vals value)))
    (sequential? value) (every? json-value? value)
    :else false))

(defmacro def-assign-policy
  "Define an inert named assignment-policy declaration.

  The string is both the var docstring and the durable policy prose. Register
  it on a runtime with `register-assign-policy!` or `use-assign-policy!`.

  ```clojure
  (def-assign-policy close-on-complete
    \"Once complete, close your assigned kanban feature\")
  ```"
  [policy-sym text]
  (when-not (simple-symbol? policy-sym)
    (throw (ex-info "def-assign-policy name must be a simple symbol"
                    {:name policy-sym})))
  (when-not (and (string? text) (not (str/blank? text)))
    (throw (ex-info "def-assign-policy text must be a non-blank string"
                    {:name policy-sym})))
  `(def ~policy-sym
     ~text
     {:kind :assign-policy
      :name ~(keyword policy-sym)
      :text ~text}))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(def-assign-policy close-on-complete
  "When the work is complete, close the assigned feature yourself with
  `strand kanban finish <target-id>`. The coordinator does not need to accept
  it separately.")

#_{:clj-kondo/ignore [:unresolved-symbol]}
(def-assign-policy stop-on-complete
  "When the work is complete, leave the assigned feature open and return a
  useful result for coordinator acceptance.")

(s/def ::id (s/and string? (complement str/blank?)))
(s/def ::name (s/and keyword? simple-keyword?))
(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::kind #{:assign-policy})
(s/def ::policy-declaration
  (s/and (s/keys :req-un [::kind ::name ::text])
         #(= #{:kind :name :text} (set (keys %)))))
(s/def ::harness :ct.spools.harnesses/name-ref)
(s/def ::target ::id)
(s/def ::cwd (s/and string? (complement str/blank?)))
(s/def ::policy #(or (string? %) (keyword? %)))
(s/def ::request-id (s/and string? (complement str/blank?)))
(s/def ::by-identity ::id)
(s/def ::after ::id)
(s/def ::title (s/and string? (complement str/blank?)))
(s/def ::append-system-prompt (s/and string? (complement str/blank?)))
(s/def ::context (s/and map? json-value?))
(def ^:private assign-keys
  #{:harness :target :cwd :policy :request-id :by-identity :after
    :title :attributes :append-system-prompt})
(s/def ::assign-request
  (s/and
   (s/keys :req-un [::harness ::target ::cwd]
           :opt-un [::policy ::request-id ::by-identity ::after
                    ::title ::attributes ::append-system-prompt])
   #(every? assign-keys (keys %))))
(s/def ::resume-assigned-request
  (s/and
   (s/keys :opt-un [:ct.spools.harnesses/prompt ::cwd
                    :ct.spools.harnesses/attributes
                    :ct.spools.harnesses/mode ::title ::by-identity
                    ::target ::context ::request-id])
   #(every? #{:prompt :cwd :attributes :mode :title :by-identity
              :target :context :request-id}
            (keys %))))

(defn register-assign-policy!
  "Install one policy declaration into runtime-owned assignment state.

  A later registration with the same name replaces the live text. Already
  accepted runs keep the prose frozen on their context."
  [rt declaration]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "register-assign-policy! requires a Weaver runtime")
  (require-valid! ::policy-declaration declaration
                  "register-assign-policy! requires a policy declaration")
  (let [entry {:name (name (:name declaration))
               :text (:text declaration)}]
    (swap! (policies rt) assoc (policy-key (:name declaration)) entry)
    entry))

(defn use-assign-policy!
  "Register one or more policy declarations on `rt`.

  Call this from a lifecycle open function that already has a runtime. The
  assignment resource open installs the shipped defaults."
  [rt & declarations]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "use-assign-policy! requires a Weaver runtime")
  (mapv #(register-assign-policy! rt %) declarations))

(defn assign-policy
  "Return the registered policy for `policy-name`, or nil when absent."
  [rt policy-name]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "assign-policy requires a Weaver runtime")
  (get @(policies rt) (policy-key policy-name)))

(defn assign-policies
  "Return registered policies as a name-sorted vector."
  [rt]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "assign-policies requires a Weaver runtime")
  (->> (vals @(policies rt))
       (sort-by :name)
       vec))

(defn target-ready?
  "Return true when `target-id` is an active strand in `weaver/ready`.

  This is the generic graph predicate core scheduling should consult. It does
  not add a `depends-on` from the run to the card. A missing or closed target
  is not ready."
  [rt target-id]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "target-ready? requires a Weaver runtime")
  (require-valid! ::id target-id "target-ready? requires a target id")
  (let [target (weaver/show rt target-id)]
    (boolean
     (and target
          (= "active" (:state target))
          (some #(= target-id (:id %)) (weaver/ready rt))))))

(defn launch-ready?
  "Return true when `run` may start.

  Ad-hoc runs (no target) are launch-ready. A targeted run is launch-ready
  only when its target is ready and an assignment bridge has finished its
  post-publication identity and run-id enrichment."
  [rt run]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "launch-ready? requires a Weaver runtime")
  (let [context (attr-get run :harness/context)
        assignment? (and (map? context)
                         (or (contains? context "assignment/policy")
                             (contains? context :assignment/policy)))
        enriched? (and assignment?
                       (or (contains? context "assignment/run-id")
                           (contains? context :assignment/run-id)))]
    (if-let [target (attr-get run :harness/target)]
      (and (or (not assignment?) enriched?)
           (target-ready? rt target))
      true)))

(defn- resolve-policy
  [rt request]
  (if-let [frozen-text (:frozen-text request)]
    {:name (or (some-> (:frozen-name request) str) default-policy-name)
     :text frozen-text}
    (let [requested (or (:policy request) default-policy-name)
          found (assign-policy rt requested)]
      (when-not found
        (fail! "Unknown assign policy"
               {:policy requested
                :available (mapv :name (assign-policies rt))}))
      found)))

(defn assign!
  "Accept an assignment and return the published ready run that serves `target`.

  Validates the target and named policy before any run exists. Unknown policy,
  a closed, missing, or foreign target, and a competing active writer all fail
  before `create!`. The run is accepted even when the target is blocked;
  `launch-ready?` stays false until the target's `depends-on` blockers close.

  The caller must supply `:cwd`. No worktree is created. The card is not
  claimed. The run is not linked to the card with `depends-on`.

  `:policy` defaults to `stop-on-complete`. Name and resolved text are frozen
  into `:harness/context` and the work prompt. `:after` starts a fresh
  provider session on the predecessor's target and frozen guidance."
  [rt request]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "assign! requires a Weaver runtime")
  (require-valid! ::assign-request request "assign! requires a valid request")
  (let [request (if (:after request)
                  (internal/inherit-from-predecessor rt request)
                  request)
        target (internal/require-target rt (:target request))
        root-targets (if (contains? request :root-targets)
                       (:root-targets request)
                       (mapv :id (internal/root-targets rt target)))
        cwd (internal/require-cwd (:cwd request))
        policy (resolve-policy rt request)
        frozen (cond-> (internal/freeze-context target cwd policy)
                 (:after request)
                 (assoc "assignment/after" (:after request)))
        guidance (internal/build-guidance {:target target
                                           :cwd cwd
                                           :policy policy})
        accepted (internal/accept-run!
                  rt {:harness (:harness request)
                      :cwd cwd
                      :target (:id target)
                      :root-targets root-targets
                      :context frozen
                      :guidance guidance
                      :system-guidance (internal/build-system-guidance
                                        {:target target
                                         :cwd cwd
                                         :policy policy})
                      :policy policy
                      :request-id (:request-id request)
                      :logical-id (:logical-id request)
                      :after (:after request)
                      :by-identity (:by-identity request)
                      :title (or (:title request)
                                 (str "Assign: " (:title target)))
                      :attributes (:attributes request)
                      :append-system-prompt
                      (:append-system-prompt request)})]
    (if (:existing? accepted)
      (:run accepted)
      (internal/enrich-guidance!
       rt (:run accepted) frozen policy cwd target))))

(s/fdef assign!
  :args (s/cat :runtime :ct.spools.harnesses/runtime :request ::assign-request)
  :ret :ct.spools.harnesses/strand)

(defn resume-assigned!
  "Resume a settled assigned run, retaining target and frozen guidance.

  Native session resume stays with `harnesses/resume!`. This wrapper always
  forwards the predecessor's target and context so a config change cannot
  rewrite the accepted policy."
  [rt predecessor-id request]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "resume-assigned! requires a Weaver runtime")
  (require-valid! ::id predecessor-id
                  "resume-assigned! requires a predecessor id")
  (require-valid! ::resume-assigned-request request
                  "resume-assigned! requires a valid request")
  (let [predecessor (internal/require-assigned-run rt predecessor-id)
        target (attr-get predecessor :harness/target)
        context (attr-get predecessor :harness/context)]
    (when-not target
      (fail! "Assigned resume requires a retained target"
             {:id predecessor-id}))
    (harnesses/resume! rt predecessor-id
                       (cond-> request
                         true (assoc :target target :context context)))))

(defn- shipped-policy
  [policy-sym]
  (var-get (ns-resolve 'ct.spools.harnesses.assignment policy-sym)))

(defn open-assignment!
  "Install shipped assignment policies for the module lifetime."
  [{:keys [runtime]}]
  (require-valid! :ct.spools.harnesses/runtime runtime
                  "assignment open received an invalid runtime")
  (state runtime)
  (use-assign-policy!
   runtime
   (shipped-policy 'close-on-complete)
   (shipped-policy 'stop-on-complete))
  {:opened :assignment
   :policies (mapv :name (assign-policies runtime))})

(defn close-assignment!
  "Close the assignment resource while retaining runtime policy state."
  [_context]
  {:closed :assignment})

(lifecycle/defresource assignment-runtime
  "Own the assignment policy registry for the module lifetime."
  {:open 'ct.spools.harnesses.assignment/open-assignment!
   :close 'ct.spools.harnesses.assignment/close-assignment!
   :after #{:harness-core-runtime}})

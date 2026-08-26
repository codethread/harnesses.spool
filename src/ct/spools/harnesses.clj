(ns ct.spools.harnesses
  "Provider-neutral structure, registry, and lifecycle for harness runs."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.identity :as identity]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.util UUID]))

(def ^:private registry-version 1)
(def ^:private overlay-prefix "harness.")
(declare ^:private json-value? new-registry registry name-string normalize-overlay
         mode-keyword run-title require-run require-phase)

(defn- overlay-key? [k]
  (let [attribute (if (keyword? k)
                    (if-let [n (namespace k)] (str n "/" (name k)) (name k))
                    (str k))]
    (or (#{"harness/model" "harness/effort" "harness/extra-argv"} attribute)
        (str/starts-with? attribute overlay-prefix))))

(s/def ::runtime map?)
(s/def ::name-ref #(or (keyword? %) (symbol? %) (and (string? %) (not (str/blank? %)))))
(s/def ::id (s/and string? (complement str/blank?)))
(s/def ::title (s/and string? (complement str/blank?)))
(s/def ::state #{"active" "closed"})
(s/def ::attributes map?)
(s/def ::doc (s/and string? (complement str/blank?)))
(s/def ::effort
  #(or (keyword? %)
       (and (string? %) (not (str/blank? %)))))
(s/def ::model (s/and string? (complement str/blank?)))
(s/def ::strand (s/keys :req-un [::id ::title ::state ::attributes]))
(s/def ::mode #{:headless :interactive "headless" "interactive"})
(s/def ::modes (s/coll-of #{:headless :interactive} :kind set? :min-count 1))
(s/def ::callback qualified-symbol?)
(s/def ::prepare ::callback)
(s/def ::finish ::callback)
(s/def ::overlay-attributes
  (s/and map?
         #(every? overlay-key? (keys %))
         #(every? json-value? (vals %))))
(s/def ::harness-definition
  (s/and
   (s/keys :req-un [::modes ::prepare ::finish]
           :opt-un [::attributes])
   #(qualified-symbol? (:prepare %))
   #(qualified-symbol? (:finish %))
   #(every? #{:modes :prepare :finish :attributes} (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? ::overlay-attributes (:attributes %)))))
(s/def ::alias-descriptor
  (s/and (s/keys :req-un [::doc ::parent ::attributes])
         #(every? #{:doc :parent :model :effort :attributes} (keys %))
         #(s/valid? ::name-ref (:parent %))
         #(or (not (contains? % :model))
              (s/valid? ::model (:model %)))
         #(or (not (contains? % :effort))
              (s/valid? ::effort (:effort %)))
         #(s/valid? ::overlay-attributes (:attributes %))))
(s/def ::alias-result
  (s/and (s/keys :req-un [:ct.spools.harnesses/alias ::doc
                          :ct.spools.harnesses/parent ::attributes])
         #(every? #{:alias :doc :parent :model :effort :attributes} (keys %))
         #(or (not (contains? % :model))
              (s/valid? ::model (:model %)))
         #(or (not (contains? % :effort))
              (s/valid? ::effort (:effort %)))))
(s/def ::alias string?)
(s/def ::parent ::name-ref)
(s/def ::harness ::name-ref)
(s/def ::definition ::harness-definition)
(s/def ::generated ::overlay-attributes)
(s/def ::resolved-harness
  (s/keys :req-un [::alias ::harness ::definition ::generated]))
(s/def ::name string?)
(s/def ::kind #{"harness" "alias"})
(s/def ::alias-of string?)
(s/def ::mode-name #{"headless" "interactive"})
(s/def :ct.spools.harnesses.registry/modes (s/coll-of ::mode-name :kind vector? :min-count 1))
(s/def ::registry-entry
  (s/or :harness
        (s/and (s/keys :req-un [::name ::kind
                                :ct.spools.harnesses.registry/modes])
               #(= "harness" (:kind %))
               #(every? #{:name :kind :modes} (keys %)))
        :alias
        (s/and (s/keys :req-un [::name ::kind ::doc ::alias-of ::attributes])
               #(= "alias" (:kind %))
               #(every? #{:name :kind :doc :alias-of :model :effort :attributes}
                        (keys %)))))
(s/def ::registry-list (s/coll-of ::registry-entry :kind vector?))
(s/def ::harness-registration (s/keys :req-un [::harness ::definition]))
(s/def ::prompt string?)
(s/def ::argv
  (s/coll-of (s/and string? (complement str/blank?))
             :kind vector? :min-count 1))
(s/def ::stdin (s/nilable string?))
(s/def ::launch-spec
  (s/and
   (s/keys :req-un [::argv ::stdin])
   #(every? #{:argv :stdin} (keys %))))
(s/def ::cwd (s/and string? (complement str/blank?)))
(s/def ::session-id (s/and string? (complement str/blank?)))
(s/def ::resumes ::id)
(s/def ::create-request
  (s/and
   (s/keys :req-un [::harness]
           :opt-un [::mode ::prompt ::cwd ::attributes ::title ::resumes ::session-id])
   #(every? #{:harness :mode :prompt :cwd :attributes :title :resumes :session-id}
            (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? ::overlay-attributes (:attributes %)))))
(s/def ::status #{:done :failed "done" "failed"})
(s/def ::exit-code (s/nilable int?))
(s/def ::result (s/nilable string?))
(s/def ::error (s/nilable string?))
(s/def ::outcome
  (s/and
   (s/keys :req-un [::status]
           :opt-un [::exit-code ::result ::session-id ::error])
   #(every? #{:status :exit-code :result :session-id :error} (keys %))))
(s/def ::retry-request
  (s/and
   (s/keys :opt-un [::harness ::cwd ::attributes])
   #(every? #{:harness :cwd :attributes} (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? ::overlay-attributes (:attributes %)))))
(s/def ::run-id ::id)
(s/def ::identity ::id)
(s/def ::resume-selector
  (s/and
   (s/keys :opt-un [::run-id ::session-id ::identity])
   #(= 1 (count (select-keys % [:run-id :session-id :identity])))
   #(every? #{:run-id :session-id :identity} (keys %))))
(s/def ::resume-request
  (s/and
   (s/keys :opt-un [::prompt ::cwd ::attributes ::mode ::title])
   #(every? #{:prompt :cwd :attributes :mode :title} (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? ::overlay-attributes (:attributes %)))))
(defn register-harness!
  "Register or replace a concrete harness definition.

  The runtime-local definition names its supported modes and qualified prepare
  and finish callbacks. Returns the normalized registration."
  [rt harness-name definition]
  (require-valid! ::runtime rt "register-harness! requires a Weaver runtime")
  (require-valid! ::name-ref harness-name "register-harness! requires a harness name")
  (require-valid! ::harness-definition definition
                  "register-harness! requires a valid harness definition")
  (let [harness-name (name-string harness-name "Harness name")
        definition (update definition :attributes normalize-overlay)]
    (swap! (:harnesses (registry rt)) assoc harness-name definition)
    (require-valid! ::harness-registration
                    {:harness harness-name :definition definition}
                    "register-harness! produced an invalid registration")))

(s/fdef register-harness! :args (s/cat :runtime ::runtime :harness-name ::name-ref :definition ::harness-definition) :ret ::harness-registration)

(defn unregister-harness!
  "Remove one concrete harness registration from the runtime registry.

  Return whether a registration existed. Aliases that name the removed harness
  remain visible and fail loudly if resolved until their owner updates them."
  [rt harness-name]
  (require-valid! ::runtime rt "unregister-harness! requires a Weaver runtime")
  (require-valid! ::name-ref harness-name
                  "unregister-harness! requires a harness name")
  (let [harness-name (name-string harness-name "Harness name")
        removed? (contains? @(:harnesses (registry rt)) harness-name)]
    (swap! (:harnesses (registry rt)) dissoc harness-name)
    removed?))

(defn register-alias!
  "Register or replace an alias over a harness or another alias.

  The descriptor documents the alias and names its parent, optional core model
  and effort, and provider attributes. Child values replace parent values."
  [rt alias-name {:keys [doc parent model effort attributes] :as descriptor}]
  (require-valid! ::runtime rt "register-alias! requires a Weaver runtime")
  (require-valid! ::name-ref alias-name "register-alias! requires an alias name")
  (require-valid! ::alias-descriptor descriptor
                  "register-alias! requires a valid alias descriptor")
  (let [alias-name (name-string alias-name "Alias name")
        entry (cond-> {:doc doc
                       :parent (name-string parent "Alias parent")
                       :attributes (normalize-overlay attributes)}
                model (assoc :model model)
                effort (assoc :effort effort))]
    (swap! (:aliases (registry rt)) assoc alias-name entry)
    (require-valid! ::alias-result
                    (assoc entry :alias alias-name)
                    "register-alias! produced an invalid registration")))

(s/fdef register-alias!
  :args (s/cat :runtime ::runtime :alias-name ::name-ref
               :descriptor ::alias-descriptor)
  :ret ::alias-result)

(defn unregister-alias!
  "Remove one alias registration from the runtime registry.

  Return whether a registration existed. Child aliases that name the removed
  alias remain visible and fail loudly if resolved until their owner updates
  them."
  [rt alias-name]
  (require-valid! ::runtime rt "unregister-alias! requires a Weaver runtime")
  (require-valid! ::name-ref alias-name
                  "unregister-alias! requires an alias name")
  (let [alias-name (name-string alias-name "Alias name")
        removed? (contains? @(:aliases (registry rt)) alias-name)]
    (swap! (:aliases (registry rt)) dissoc alias-name)
    removed?))

(defn resolve-harness
  "Resolve a harness or alias into its implementation and merged attributes.

  Alias layers are ordinary maps: child values replace parent values. Cycles and
  missing parents fail loudly."
  [rt requested]
  (require-valid! ::runtime rt "resolve-harness requires a Weaver runtime")
  (let [requested (name-string requested "Harness")
        {:keys [harnesses aliases]} (registry rt)]
    (loop [cursor requested seen #{} layers []]
      (when (contains? seen cursor)
        (fail! "Harness alias cycle" {:requested requested :at cursor}))
      (if-let [alias (get @aliases cursor)]
        (recur (:parent alias) (conj seen cursor) (conj layers alias))
        (if-let [definition (get @harnesses cursor)]
          (let [layers (reverse layers)
                attributes (apply merge (:attributes definition)
                                  (map :attributes layers))
                model (some :model (reverse layers))
                effort (some :effort (reverse layers))]
            (require-valid!
             ::resolved-harness
             {:alias requested
              :harness cursor
              :definition definition
              :generated (cond-> attributes
                           model (assoc :harness/model model)
                           effort (assoc :harness/effort (name effort)))}
             "resolve-harness produced an invalid resolution"))
          (fail! "Harness or alias is not registered"
                 {:requested requested :missing cursor}))))))

(s/fdef resolve-harness :args (s/cat :runtime ::runtime :requested ::name-ref) :ret ::resolved-harness)

(defn concrete-harness
  "Return a registered concrete harness definition by name.

  Fail when the name is absent or points at invalid runtime data."
  [rt harness-name]
  (require-valid! ::runtime rt "concrete-harness requires a Weaver runtime")
  (require-valid! ::name-ref harness-name "concrete-harness requires a harness name")
  (require-valid!
   ::harness-definition
   (or (get @(:harnesses (registry rt)) (name-string harness-name "Concrete harness"))
       (fail! "Concrete harness is not registered" {:harness harness-name}))
   "concrete-harness found an invalid definition"))

(s/fdef concrete-harness :args (s/cat :runtime ::runtime :harness-name ::name-ref) :ret ::harness-definition)

(defn harnesses
  "Return registered concrete harnesses and aliases as plain data."
  [rt]
  (require-valid! ::runtime rt "harnesses requires a Weaver runtime")
  (let [{:keys [harnesses aliases]} (registry rt)]
    (require-valid!
     ::registry-list
     (vec
      (concat
       (for [[name definition] (sort-by key @harnesses)]
         {:name name :kind "harness" :modes (mapv clojure.core/name (:modes definition))})
       (for [[name {:keys [doc parent model effort attributes]}]
             (sort-by key @aliases)]
         (cond-> {:name name
                  :kind "alias"
                  :doc doc
                  :alias-of parent
                  :attributes attributes}
           model (assoc :model model)
           effort (assoc :effort effort)))))
     "harnesses produced an invalid registry listing")))

(s/fdef harnesses :args (s/cat :runtime ::runtime) :ret ::registry-list)

(defn create!
  "Create and return one pending harness-run strand.

  Resolves the requested alias, merges provider overrides, assigns a session ID,
  and records frozen reconstruction data. Headless runs require a prompt."
  [rt {:keys [harness mode prompt cwd attributes title resumes session-id] :as request}]
  (require-valid! ::runtime rt "create! requires a Weaver runtime")
  (require-valid! ::create-request request "create! requires a valid run request")
  (let [mode (mode-keyword (or mode :headless))
        {:keys [alias harness definition generated]} (resolve-harness rt harness)
        overrides (normalize-overlay attributes)
        effective (merge generated overrides)
        cwd (or cwd (System/getProperty "user.dir"))
        session-id (or session-id (str (UUID/randomUUID)))]
    (when-not (contains? (:modes definition) mode)
      (fail! "Harness does not support requested mode"
             {:harness harness :mode mode :modes (:modes definition)}))
    (when (and (= :headless mode) (str/blank? prompt))
      (fail! "Headless harness run requires a prompt" {:harness alias}))
    (let [run (require-valid!
               ::strand
               (weaver/add!
                rt
                (cond-> {:title (or title (run-title alias mode prompt))
                         :attributes
                         (merge
                          {:harness/run "true"
                           :harness/alias alias
                           :harness/harness harness
                           :harness/mode (name mode)
                           :harness/phase "pending"
                           :harness/cwd cwd
                           :harness/session-id session-id
                           :harness/generated generated
                           :harness/overrides overrides}
                          effective
                          (when-not (str/blank? prompt) {:harness/prompt prompt})
                          (when resumes {:harness/resumes resumes}))}
                  resumes (assoc :edges [{:type "resumes" :to resumes}])))
               "create! produced an invalid run strand")
          predecessor (when resumes (require-run rt resumes))
          identity-binding (identity/bind!
                            rt
                            (cond-> {:harness harness
                                     :native-session-id session-id
                                     :run-id (:id run)}
                              predecessor
                              (assoc :expected-identity
                                     (attr-get predecessor :identity/id))))]
      (require-valid!
       ::strand
       (weaver/update!
        rt (:id run)
        {:attributes {:identity/id (:identity identity-binding)
                      :identity/prompt (:prompt identity-binding)}})
       "create! produced an invalid identity-bound run"))))

(s/fdef create! :args (s/cat :runtime ::runtime :request ::create-request) :ret ::strand)

(defn mark-running!
  "Transition and return a pending run as running."
  [rt id]
  (require-valid! ::runtime rt "mark-running! requires a Weaver runtime")
  (require-valid! ::id id "mark-running! requires a run id")
  (require-phase (require-run rt id) "pending")
  (require-valid! ::strand
                  (weaver/update! rt id {:attributes {:harness/phase "running"}})
                  "mark-running! produced an invalid run strand"))

(s/fdef mark-running! :args (s/cat :runtime ::runtime :id ::id) :ret ::strand)

(defn finish!
  "Record and return a terminal provider-neutral outcome.

  Successful runs close; failed runs remain active so they can be retried."
  [rt id {:keys [status exit-code result session-id error] :as outcome}]
  (require-valid! ::runtime rt "finish! requires a Weaver runtime")
  (require-valid! ::id id "finish! requires a run id")
  (require-valid! ::outcome outcome "finish! requires a valid outcome")
  (let [run (require-run rt id)
        phase (attr-get run :harness/phase)
        status (if (keyword? status) status (keyword (str status)))]
    (when-not (and (#{:pending :running} (keyword phase)) (#{:done :failed} status))
      (fail! "Harness finish transition is invalid" {:id id :phase phase :status status}))
    (when (and (= :done status) (not= 0 exit-code))
      (fail! "Successful harness outcome requires exit code zero" {:id id :exit-code exit-code}))
    (when (and (= :done status)
               (= "headless" (attr-get run :harness/mode))
               (str/blank? result))
      (fail! "Successful headless harness outcome requires a result" {:id id}))
    (require-valid!
     ::strand
     (weaver/update!
      rt id
      {:state (if (= :done status) "closed" "active")
       :attributes
       {:harness/phase (name status)
        :harness/exit-code exit-code
        :harness/result result
        :harness/session-id (or session-id (attr-get run :harness/session-id))
        :harness/error (when (= :failed status)
                         (or error "Harness process failed"))}})
     "finish! produced an invalid run strand")))

(s/fdef finish! :args (s/cat :runtime ::runtime :id ::id :outcome ::outcome) :ret ::strand)

(defn self-complete!
  "Record best-effort result text for an interactive run.

  This optional user-driven signal does not change the run lifecycle."
  [rt id result]
  (require-valid! ::runtime rt "self-complete! requires a Weaver runtime")
  (require-valid! ::id id "self-complete! requires a run id")
  (require-valid! string? result "self-complete! requires result text")
  (let [run (require-run rt id)]
    (when-not (= "interactive" (attr-get run :harness/mode))
      (fail! "self-complete applies only to interactive runs" {:id id}))
    (require-valid! ::strand
                    (weaver/update! rt id {:attributes {:harness/result result}})
                    "self-complete! produced an invalid run strand")))

(s/fdef self-complete! :args (s/cat :runtime ::runtime :id ::id :result string?) :ret ::strand)

(defn retry!
  "Reconstruct and reset one failed run, applying replacement options.

  A live alias is resolved again when possible; otherwise its frozen generated
  data is retained. Explicit overrides win and nil removes an old override."
  [rt id {:keys [harness cwd attributes] :as request}]
  (require-valid! ::runtime rt "retry! requires a Weaver runtime")
  (require-valid! ::id id "retry! requires a run id")
  (require-valid! ::retry-request request "retry! requires valid replacements")
  (let [run (require-phase (require-run rt id) "failed")
        old-attrs (:attributes run)
        old-generated (normalize-overlay (attr-get run :harness/generated))
        old-overrides (normalize-overlay (attr-get run :harness/overrides))
        requested (or harness (attr-get run :harness/alias))
        explicit-alias? (some? harness)
        resolved (try
                   (resolve-harness rt requested)
                   (catch Exception e
                     (when explicit-alias?
                       (throw e))))
        generated (or (:generated resolved) old-generated)
        concrete (or (:harness resolved) (attr-get run :harness/harness))
        _ (concrete-harness rt concrete)
        call-overrides (normalize-overlay attributes)
        overrides (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
                             old-overrides call-overrides)
        effective (merge generated overrides)
        old-overlay-keys (set (filter overlay-key? (keys old-attrs)))
        all-overlay-keys (into old-overlay-keys (keys effective))
        overlay-delta (into {} (map (fn [k] [k (get effective k)]) all-overlay-keys))
        generated-delta (into {}
                              (map (fn [k] [k (get generated k)]))
                              (into (set (keys old-generated)) (keys generated)))
        overrides-delta (into {}
                              (map (fn [k] [k (get overrides k)]))
                              (into (set (keys old-overrides)) (keys overrides)))
        resumed? (some? (attr-get run :harness/resumes))]
    (require-valid!
     ::strand
     (weaver/update!
      rt id
      {:attributes
       (merge overlay-delta
              {:harness/alias requested
               :harness/harness concrete
               :harness/cwd (or cwd (attr-get run :harness/cwd))
               :harness/phase "pending"
               :harness/generated generated-delta
               :harness/overrides overrides-delta
               :harness/session-id (if resumed?
                                     (attr-get run :harness/session-id)
                                     (str (UUID/randomUUID)))
               :harness/error nil
               :harness/result nil
               :harness/exit-code nil})})
     "retry! produced an invalid run strand")))

(s/fdef retry! :args (s/cat :runtime ::runtime :id ::id :request ::retry-request) :ret ::strand)

(defn resolve-resume-run
  "Resolve one completed predecessor from exactly one selector.

  `selector` contains one of `:run-id`, `:session-id`, or `:identity`.
  Run IDs resolve exactly; session and identity selectors choose the most
  recently updated completed run. Missing, conflicting, and unmatched
  selectors fail loudly."
  [rt selector]
  (require-valid! ::runtime rt "resolve-resume-run requires a Weaver runtime")
  (require-valid! ::resume-selector selector
                  "resolve-resume-run requires exactly one selector")
  (if-let [run-id (:run-id selector)]
    (require-phase (require-run rt run-id) "done")
    (let [[attribute value] (if-let [session-id (:session-id selector)]
                              [:harness/session-id session-id]
                              [:identity/id (:identity selector)])
          matches (->> (weaver/list rt)
                       (filter #(and (= "true" (attr-get % :harness/run))
                                     (= "done" (attr-get % :harness/phase))
                                     (= value (attr-get % attribute))))
                       vec)
          resumed-ids (into #{}
                            (map :to_strand_id)
                            (graph/outgoing-edges rt (mapv :id matches) "resumes"))
          latest (->> matches
                      (remove #(contains? resumed-ids (:id %)))
                      (sort-by (juxt :updated_at :id) #(compare %2 %1))
                      first)]
      (or latest
          (fail! "No completed harness run matches resume selector"
                 {:selector selector})))))

(s/fdef resolve-resume-run
  :args (s/cat :runtime ::runtime :selector ::resume-selector)
  :ret ::strand)

(defn resume!
  "Create a new run that resumes one successful provider session.

  The new strand points to its predecessor and reuses its provider session ID."
  [rt id {:keys [prompt cwd attributes mode title] :as request}]
  (require-valid! ::runtime rt "resume! requires a Weaver runtime")
  (require-valid! ::id id "resume! requires a predecessor run id")
  (require-valid! ::resume-request request "resume! requires valid continuation options")
  (let [run (require-phase (require-run rt id) "done")
        session-id (attr-get run :harness/session-id)
        retained (normalize-overlay (attr-get run :harness/overrides))
        replacements (normalize-overlay attributes)
        overrides (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
                             retained replacements)]
    (when (str/blank? session-id)
      (fail! "Resume predecessor has no session id" {:id id}))
    (create! rt
             (cond-> {:harness (attr-get run :harness/alias)
                      :mode (or mode (attr-get run :harness/mode))
                      :cwd (or cwd (attr-get run :harness/cwd))
                      :attributes overrides
                      :resumes id
                      :session-id session-id}
               (some? prompt) (assoc :prompt prompt)
               (some? title) (assoc :title title)))))

(s/fdef resume! :args (s/cat :runtime ::runtime :id ::id :request ::resume-request) :ret ::strand)

(defn open-harness-core!
  "Open the provider-neutral harness registry for a module lifetime."
  [{:keys [runtime]}]
  (require-valid! ::runtime runtime "harness-core open received an invalid runtime")
  (registry runtime)
  {:opened :harness-core})

(defn close-harness-core!
  "Close the harness-core module resource while retaining runtime state."
  [_context]
  {:closed :harness-core})

(defn- json-value? [value]
  (cond
    (or (nil? value) (string? value) (number? value) (boolean? value)) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-value? (vals value)))
    (sequential? value) (every? json-value? value)
    :else false))

(defn- new-registry []
  {:harnesses (atom {})
   :aliases (atom {})})

(defn- registry [rt]
  (runtime/spool-state rt ::registry {:version registry-version} new-registry))

(defn- name-string [v context]
  (let [s (cond
            (or (keyword? v) (symbol? v)) (name v)
            (string? v) v
            :else nil)]
    (if (and s (not (str/blank? s)))
      s
      (fail! (str context " must be a non-blank name") {:value v}))))

(defn- normalize-overlay [m]
  (into {}
        (map (fn [[k v]]
               (let [k (if (keyword? k) k (keyword (str k)))]
                 (when-not (overlay-key? k)
                   (fail! "Harness overrides may contain only harness.<provider>/* attributes"
                          {:attribute k}))
                 [k v])))
        (or m {})))

(defn- mode-keyword [mode]
  (let [mode (if (keyword? mode) mode (keyword (str mode)))]
    (if (#{:headless :interactive} mode)
      mode
      (fail! "Harness mode must be headless or interactive" {:mode mode}))))

(defn- run-title [alias mode prompt]
  (if-not (str/blank? prompt)
    (subs prompt 0 (min 80 (count prompt)))
    (str alias " " (name mode) " run")))

(defn- require-run [rt id]
  (let [run (or (weaver/show rt id) (fail! "Harness run not found" {:id id}))]
    (when-not (= "true" (attr-get run :harness/run))
      (fail! "Strand is not a harness run" {:id id}))
    run))

(defn- require-phase [run phase]
  (when-not (= phase (attr-get run :harness/phase))
    (fail! "Harness run has invalid phase for operation"
           {:id (:id run) :expected phase :actual (attr-get run :harness/phase)}))
  run)

(lifecycle/defresource harness-core-runtime
  "Own the provider-neutral harness registry for the module lifetime."
  {:open 'ct.spools.harnesses/open-harness-core!
   :close 'ct.spools.harnesses/close-harness-core!})

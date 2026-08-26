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

(def ^:private registry-version 2)
(def ^:private overlay-prefix "harness.")
(declare ^:private availability* condition-result json-value? new-registry
         registry name-string normalize-alias-candidate normalize-overlay
         reference-string mode-keyword run-title require-run require-phase)

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
(s/def ::condition
  #(or (s/valid? ::name-ref %)
       (and (vector? %)
            (cond
              (= :not (first %))
              (and (= 2 (count %)) (s/valid? ::condition (second %)))

              (#{:and :or} (first %))
              (and (< 1 (count %))
                   (every? (partial s/valid? ::condition) (rest %)))

              :else false))))
(s/def ::when ::condition)
(s/def ::alias-candidate
  (s/and (s/keys :req-un [::doc ::parent ::attributes]
                 :opt-un [::when])
         #(every? #{:doc :parent :model :effort :attributes :when} (keys %))
         #(s/valid? ::name-ref (:parent %))
         #(or (not (contains? % :model)) (s/valid? ::model (:model %)))
         #(or (not (contains? % :effort)) (s/valid? ::effort (:effort %)))
         #(s/valid? ::overlay-attributes (:attributes %))))
(s/def ::alias-descriptor
  #(or (s/valid? ::alias-candidate %)
       (and (vector? %) (seq %) (every? (partial s/valid? ::alias-candidate) %))))
(s/def ::candidates (s/coll-of ::alias-candidate :kind vector? :min-count 1))
(s/def ::alias-result (s/keys :req-un [:ct.spools.harnesses/alias ::candidates]))
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
(s/def ::available boolean?)
(s/def ::unavailable-reasons (s/coll-of map? :kind vector?))
(s/def ::selected-candidate nat-int?)
(s/def ::selected-parent string?)
(s/def ::registry-entry
  (s/or :harness
        (s/and (s/keys :req-un [::name ::kind
                                :ct.spools.harnesses.registry/modes ::available]
                       :opt-un [::harness ::unavailable-reasons])
               #(= "harness" (:kind %))
               #(every? #{:name :kind :modes :available :harness
                          :unavailable-reasons}
                        (keys %)))
        :alias
        (s/and (s/keys :req-un [::name ::kind ::candidates ::available]
                       :opt-un [::harness ::selected-candidate ::selected-parent
                                ::unavailable-reasons])
               #(= "alias" (:kind %))
               #(every? #{:name :kind :candidates :available :harness
                          :selected-candidate :selected-parent
                          :unavailable-reasons}
                        (keys %)))))
(s/def ::registry-list (s/coll-of ::registry-entry :kind vector?))
(s/def ::harness-registration (s/keys :req-un [::harness ::definition]))
(s/def ::prompt string?)
(s/def ::argv
  (s/coll-of (s/and string? (complement str/blank?))
             :kind vector? :min-count 1))
(s/def ::stdin (s/nilable string?))
(s/def ::env
  (s/map-of (s/and string? #(re-matches #"[A-Za-z_][A-Za-z0-9_]*" %))
            string?))
(s/def ::launch-spec
  (s/and
   (s/keys :req-un [::argv ::stdin]
           :opt-un [::env])
   #(every? #{:argv :stdin :env} (keys %))))
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
(defn set-flag!
  "Set a runtime-local boolean configuration flag and return its new value."
  [rt flag value]
  (require-valid! ::runtime rt "set-flag! requires a Weaver runtime")
  (require-valid! ::name-ref flag "set-flag! requires a flag name")
  (require-valid! boolean? value "set-flag! requires a boolean value")
  (swap! (:flags (registry rt)) assoc (reference-string flag "Flag") value)
  value)

(defn unset-flag!
  "Remove a runtime-local configuration flag and return whether it existed."
  [rt flag]
  (require-valid! ::runtime rt "unset-flag! requires a Weaver runtime")
  (require-valid! ::name-ref flag "unset-flag! requires a flag name")
  (let [flag (reference-string flag "Flag")
        existed? (contains? @(:flags (registry rt)) flag)]
    (swap! (:flags (registry rt)) dissoc flag)
    existed?))

(defn flags
  "Return runtime-local configuration flags as a sorted map."
  [rt]
  (require-valid! ::runtime rt "flags requires a Weaver runtime")
  (into (sorted-map) @(:flags (registry rt))))

(defn flag
  "Return a runtime-local flag value, or nil when it is unset."
  [rt flag-name]
  (require-valid! ::runtime rt "flag requires a Weaver runtime")
  (require-valid! ::name-ref flag-name "flag requires a flag name")
  (get @(:flags (registry rt)) (reference-string flag-name "Flag")))

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
    (swap! (:flags (registry rt))
           #(if (contains? % (str "harness/" harness-name))
              %
              (assoc % (str "harness/" harness-name) true)))
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
  "Register or replace one or more ordered definitions for an alias.

  A map is one definition. A vector is tried in order; each complete definition
  may carry a `:when` flag expression. The first available definition wins."
  [rt alias-name descriptor]
  (require-valid! ::runtime rt "register-alias! requires a Weaver runtime")
  (require-valid! ::name-ref alias-name "register-alias! requires an alias name")
  (require-valid! ::alias-descriptor descriptor
                  "register-alias! requires a valid alias descriptor")
  (let [alias-name (name-string alias-name "Alias name")
        candidates (mapv normalize-alias-candidate
                         (if (vector? descriptor) descriptor [descriptor]))]
    (swap! (:aliases (registry rt)) assoc alias-name candidates)
    (require-valid! ::alias-result
                    {:alias alias-name :candidates candidates}
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

(defn availability
  "Return whether a registered harness or alias can currently be resolved.

  Unavailable results include structured reasons for disabled flags, rejected
  alias candidates, and missing registrations."
  [rt requested]
  (require-valid! ::runtime rt "availability requires a Weaver runtime")
  (let [requested (name-string requested "Harness")]
    (dissoc (availability* rt requested #{}) :definition :layers)))

(defn resolve-harness
  "Resolve the first available alias definition into launch data.

  Candidate definitions are considered in registration order. Their `:when`
  expressions and complete parent chains must both be available."
  [rt requested]
  (require-valid! ::runtime rt "resolve-harness requires a Weaver runtime")
  (let [requested (name-string requested "Harness")
        result (availability* rt requested #{})]
    (when-not (:available result)
      (fail! "Harness or alias is unavailable"
             {:requested requested :reasons (:unavailable-reasons result)}))
    (let [layers (:layers result)
          definition (:definition result)
          attributes (apply merge (:attributes definition) (map :attributes layers))
          model (some :model (reverse layers))
          effort (some :effort (reverse layers))]
      (require-valid!
       ::resolved-harness
       {:alias requested
        :harness (:harness result)
        :definition definition
        :generated (cond-> attributes
                     model (assoc :harness/model model)
                     effort (assoc :harness/effort (name effort)))}
       "resolve-harness produced an invalid resolution"))))

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
  "Return every registration with its current runtime availability."
  [rt]
  (require-valid! ::runtime rt "harnesses requires a Weaver runtime")
  (let [{:keys [harnesses aliases]} (registry rt)]
    (require-valid!
     ::registry-list
     (vec
      (concat
       (for [[name definition] (sort-by key @harnesses)]
         (merge {:name name
                 :kind "harness"
                 :modes (mapv clojure.core/name (:modes definition))}
                (availability rt name)))
       (for [[name candidates] (sort-by key @aliases)]
         (merge {:name name
                 :kind "alias"
                 :candidates candidates}
                (availability rt name)))))
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

  Resolve the live alias again so disabled definitions cannot be restarted.
  Explicit overrides win and nil removes an old override."
  [rt id {:keys [harness cwd attributes] :as request}]
  (require-valid! ::runtime rt "retry! requires a Weaver runtime")
  (require-valid! ::id id "retry! requires a run id")
  (require-valid! ::retry-request request "retry! requires valid replacements")
  (let [run (require-phase (require-run rt id) "failed")
        old-attrs (:attributes run)
        old-generated (normalize-overlay (attr-get run :harness/generated))
        old-overrides (normalize-overlay (attr-get run :harness/overrides))
        requested (or harness (attr-get run :harness/alias))
        resolved (resolve-harness rt requested)
        generated (:generated resolved)
        concrete (:harness resolved)
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
   :aliases (atom {})
   :flags (atom {})})

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

(defn- reference-string [v context]
  (let [s (cond
            (keyword? v) (if-let [n (namespace v)] (str n "/" (name v)) (name v))
            (symbol? v) (str v)
            (string? v) v
            :else nil)]
    (if (and s (not (str/blank? s)))
      s
      (fail! (str context " must be a non-blank name") {:value v}))))

(defn- normalize-alias-candidate
  [{:keys [parent attributes] :as candidate}]
  (assoc candidate
         :parent (name-string parent "Alias parent")
         :attributes (normalize-overlay attributes)))

(defn- condition-result [rt expression]
  (if (vector? expression)
    (let [[operator & operands] expression
          results (mapv (partial condition-result rt) operands)]
      (case operator
        :and {:passes? (every? :passes? results)
              :reasons (vec (mapcat :reasons (remove :passes? results)))}
        :or {:passes? (boolean (some :passes? results))
             :reasons (if (some :passes? results)
                        []
                        (vec (mapcat :reasons results)))}
        :not {:passes? (not (:passes? (first results)))
              :reasons (if (:passes? (first results))
                         [{:condition expression :reason "negated condition passed"}]
                         [])}))
    (let [flag-name (reference-string expression "Condition flag")
          value (flag rt flag-name)]
      {:passes? (true? value)
       :reasons (if (true? value)
                  []
                  [{:flag flag-name :value value}])})))

(defn- availability* [rt requested seen]
  (let [{:keys [harnesses aliases]} (registry rt)]
    (condp contains? requested
      seen
      {:available false
       :unavailable-reasons [{:name requested :reason "alias cycle"}]}

      @aliases
      (let [candidates (get @aliases requested)]
        (loop [index 0 remaining candidates rejected []]
          (if-let [candidate (first remaining)]
            (let [condition (if-let [expression (:when candidate)]
                              (condition-result rt expression)
                              {:passes? true :reasons []})]
              (if-not (:passes? condition)
                (recur (inc index) (next remaining)
                       (conj rejected {:candidate index
                                       :parent (:parent candidate)
                                       :reasons (:reasons condition)}))
                (let [parent (availability* rt (:parent candidate)
                                            (conj seen requested))]
                  (if (:available parent)
                    (-> parent
                        (assoc :name requested
                               :selected-candidate index
                               :selected-parent (:parent candidate))
                        (update :layers conj candidate))
                    (recur (inc index) (next remaining)
                           (conj rejected {:candidate index
                                           :parent (:parent candidate)
                                           :reasons (:unavailable-reasons parent)}))))))
            {:name requested
             :available false
             :unavailable-reasons rejected})))

      @harnesses
      (let [enabled? (not= false (flag rt (str "harness/" requested)))]
        (cond-> {:name requested :available enabled?}
          enabled? (assoc :harness requested
                          :definition (get @harnesses requested)
                          :layers [])
          (not enabled?) (assoc :unavailable-reasons
                                [{:flag (str "harness/" requested)
                                  :value false}])))

      {:name requested
       :available false
       :unavailable-reasons [{:name requested :reason "not registered"}]})))

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

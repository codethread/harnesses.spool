(ns ct.spools.harnesses.internal.registry
  "Registry helpers for concrete harnesses and aliases.

  These are named steps for `ct.spools.harnesses`. Callers still go through
  the public registration and resolution functions."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [fail!]]))

(def appended-system-prompts-attribute
  "Attribute holding stacked system-prompt fragments on a run."
  :harness/appended-system-prompts)

(def overlay-prefix
  "Provider-specific overlay attribute prefix."
  "harness.")

(defn overlay-key?
  "Return true when `k` is a permitted harness overlay attribute."
  [k]
  (let [attribute (if (keyword? k)
                    (if-let [n (namespace k)] (str n "/" (name k)) (name k))
                    (str k))]
    (or (#{"harness/model" "harness/effort" "harness/extra-argv"
           "harness/appended-system-prompts"}
         attribute)
        (str/starts-with? attribute overlay-prefix))))

(defn json-value?
  "Return true when `value` is JSON-safe for durable attributes."
  [value]
  (cond
    (or (nil? value) (string? value) (number? value) (boolean? value)) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-value? (vals value)))
    (sequential? value) (every? json-value? value)
    :else false))

(defn name-string
  "Coerce `v` to a non-blank unqualified name."
  [v context]
  (let [s (cond
            (or (keyword? v) (symbol? v)) (name v)
            (string? v) v
            :else nil)]
    (if (and s (not (str/blank? s)))
      s
      (fail! (str context " must be a non-blank name") {:value v}))))

(defn reference-string
  "Coerce `v` to a non-blank possibly-namespaced name."
  [v context]
  (let [s (cond
            (keyword? v) (if-let [n (namespace v)] (str n "/" (name v)) (name v))
            (symbol? v) (str v)
            (string? v) v
            :else nil)]
    (if (and s (not (str/blank? s)))
      s
      (fail! (str context " must be a non-blank name") {:value v}))))

(defn normalize-overlay
  "Parse one overlay map, failing on foreign keys or malformed prompts."
  [m]
  (into {}
        (map (fn [[k v]]
               (let [k (if (keyword? k) k (keyword (str k)))]
                 (when-not (overlay-key? k)
                   (fail! "Harness overrides may contain only harness.<provider>/* attributes"
                          {:attribute k}))
                 (when (and (= appended-system-prompts-attribute k)
                            (not (s/valid? :ct.spools.harnesses/appended-system-prompts v)))
                   (fail! "harness/appended-system-prompts must be a vector of non-blank strings"
                          {:appended-system-prompts v}))
                 [k v])))
        (or m {})))

(defn merge-overlays
  "Merge generated and caller overlays, concatenating system prompts."
  [generated overrides]
  (let [prompts (vec (concat (get generated appended-system-prompts-attribute [])
                             (get overrides appended-system-prompts-attribute [])))]
    (cond-> (merge generated overrides)
      (seq prompts) (assoc appended-system-prompts-attribute prompts))))

(defn normalize-alias-candidate
  "Normalize one alias candidate's parent and visibility names."
  [{:keys [parent attributes allow deny] :as candidate}]
  (cond-> (assoc candidate
                 :parent (name-string parent "Alias parent")
                 :attributes (normalize-overlay attributes))
    allow (assoc :allow (into #{} (map #(name-string % "Allowed name")) allow))
    deny (assoc :deny (into #{} (map #(name-string % "Denied name")) deny))))

(defn mode-keyword
  "Parse a harness mode keyword, failing on anything else."
  [mode]
  (let [mode (if (keyword? mode) mode (keyword (str mode)))]
    (if (#{:headless :interactive} mode)
      mode
      (fail! "Harness mode must be headless or interactive" {:mode mode}))))

(defn run-title
  "Return a display title from the prompt, or a fallback from alias and mode."
  [alias mode prompt]
  (if-not (str/blank? prompt)
    (subs prompt 0 (min 80 (count prompt)))
    (str alias " " (name mode) " run")))

(defn condition-result
  "Evaluate one alias `:when` expression against `flag-fn`."
  [expression flag-fn]
  (if (vector? expression)
    (let [[operator & operands] expression
          results (mapv #(condition-result % flag-fn) operands)]
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
          value (flag-fn flag-name)]
      {:passes? (true? value)
       :reasons (if (true? value)
                  []
                  [{:flag flag-name :value value}])})))

(defn availability*
  "Resolve whether `requested` can currently be launched.

  `harnesses` and `aliases` are the live registry maps. `flag-fn` reads one
  runtime flag. `seen` is the alias cycle set for this walk."
  [requested seen {:keys [harnesses aliases flag-fn]}]
  (condp contains? requested
    seen
    {:available false
     :unavailable-reasons [{:name requested :reason "alias cycle"}]}

    aliases
    (let [candidates (get aliases requested)]
      (loop [index 0 remaining candidates rejected []]
        (if-let [candidate (first remaining)]
          (let [condition (if-let [expression (:when candidate)]
                            (condition-result expression flag-fn)
                            {:passes? true :reasons []})]
            (if-not (:passes? condition)
              (recur (inc index) (next remaining)
                     (conj rejected {:candidate index
                                     :parent (:parent candidate)
                                     :reasons (:reasons condition)}))
              (let [parent (availability* (:parent candidate)
                                          (conj seen requested)
                                          {:harnesses harnesses
                                           :aliases aliases
                                           :flag-fn flag-fn})]
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

    harnesses
    (let [enabled? (not= false (flag-fn (str "harness/" requested)))]
      (cond-> {:name requested :available enabled?}
        enabled? (assoc :harness requested
                        :definition (get harnesses requested)
                        :layers [])
        (not enabled?) (assoc :unavailable-reasons
                              [{:flag (str "harness/" requested)
                                :value false}])))

    {:name requested
     :available false
     :unavailable-reasons [{:name requested :reason "not registered"}]}))

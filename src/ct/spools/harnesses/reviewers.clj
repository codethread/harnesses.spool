(ns ct.spools.harnesses.reviewers
  "Declarative, runtime-owned reviewer lenses for tracked agent reviews."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.registry :as harness-registry]
            [ct.spools.harnesses.internal.review-git :as review-git]
            [millstrand.api.authoring.alpha :as authoring]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [fail! require-valid!]]))

(def ^:private kind :ct.spools.harnesses.reviewers/reviewers)
(def ^:private state-key :ct.spools.harnesses.reviewers/registry)
(def ^:private allowed-options #{:seat :labels :glob :system-prompt})
(def ^:private allowed-request-keys
  #{:cwd :base :branch :git :agents :labels :max-bytes :by-identity})

(s/def ::name symbol?)
(s/def ::doc (s/and string? (complement str/blank?)))
(s/def ::seat-name (s/or :symbol symbol? :keyword keyword?))
(s/def ::seat (s/or :one ::seat-name
                    :fallback (s/coll-of ::seat-name :kind vector? :min-count 1)))
(s/def ::labels (s/coll-of (s/and string? (complement str/blank?))
                           :kind vector? :distinct true))
(s/def ::glob (s/coll-of (s/and string? (complement str/blank?))
                         :kind vector? :distinct true))
(s/def ::system-prompt (s/and string? (complement str/blank?)))
(s/def ::options
  (s/and (s/keys :req-un [::seat]
                 :opt-un [::labels ::glob ::system-prompt])
         #(every? allowed-options (keys %))))
(s/def ::prompt (s/and string? (complement str/blank?)))
(s/def ::reviewer
  (s/keys :req-un [::name ::doc ::options ::prompt]))

(authoring/register-registry-kind! kind ::reviewer)

(declare ^:private reviewer-registry describe-reviewer validate-request!
         validate-selectors! select-reviewers preflight! create-runs! run-plan)

(authoring/defauthoring reviewer [mode form-name doc options prompt-expression]
  (when-not (string? doc)
    (throw (ex-info "defreviewer requires a literal documentation string"
                    {:name form-name :doc doc})))
  (let [entry (list 'hash-map
                    :name (list 'quote form-name)
                    :doc doc
                    :options options
                    :prompt prompt-expression)]
    {:name form-name
     :definition (list 'def form-name doc entry)
     :kind kind
     :key (keyword form-name)
     :entry entry
     :use-options {}}))

(alter-meta!
 #'defreviewer assoc :doc
 "Define an inert reviewer declaration.

 The required options map accepts `:seat` as an alias symbol, keyword, or
 ordered non-empty fallback vector. Optional `:labels` and `:glob` vectors
 select changes; optional `:system-prompt` appends reviewer guidance. The final
 expression must evaluate to a non-blank prompt string. Select the resulting Var
 with `use-reviewer!`, or define and select it with `defreviewer!`.")

(defn use-reviewer-kind!
  "Select the open reviewer registry kind in the current module source."
  []
  (runtime/collect-kind!
   state-key
   {:id kind
    :entry-spec ::reviewer
    :binding-moment :module-refresh}))

(defn reviewers
  "Return declared reviewers with current ordered seat availability.

  Names and seats may be authored as ordinary symbols or keywords. Returned
  names and seats are normalized to strings for CLI composition. Seat names
  inherit the unqualified normalization used by harness alias registration."
  [rt]
  (->> (registry/effective (reviewer-registry rt) kind)
       vals
       (sort-by (comp str :name))
       (mapv #(describe-reviewer rt %))))

(defn start!
  "Capture a change and create all selected reviewer runs, then schedule them.

  `request` is closed to `:cwd`, `:base`, `:branch`, literal `:git`,
  `:max-bytes`, repeated reviewer `:agents`, repeated `:labels`, and
  `:by-identity`. The captured diff and source metadata are frozen into every
  run's durable context. Empty changes return a structured skip."
  [rt request]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "start! requires a Weaver runtime")
  (validate-request! request)
  (let [available (reviewers rt)
        _ (validate-selectors! available request)
        change (review-git/capture request)]
    (if (empty? (:paths change))
      {:status "skipped" :reason "no-changes" :change (dissoc change :diff)
       :runs [] :skips []}
      (let [{:keys [selected skips]}
            (select-reviewers available request (:paths change))
            _ (doseq [reviewer selected] (preflight! rt reviewer))
            plans (mapv #(run-plan change request %) selected)
            runs (create-runs! rt selected plans)]
        (when (seq runs)
          (execution/schedule! rt))
        {:status (if (seq runs) "scheduled" "skipped")
         :reason (when-not (seq runs) "no-matching-reviewers")
         :change (dissoc change :diff)
         :runs (mapv (fn [reviewer run]
                       {:id (:id run)
                        :reviewer (:name reviewer)
                        :seat (:selected-seat reviewer)})
                     selected runs)
         :skips skips}))))

(defn- reviewer-registry [rt]
  (runtime/spool-state rt state-key registry/registry))

(defn- seat-names [seat]
  (mapv #(harness-registry/name-string % "Reviewer seat")
        (if (vector? seat) seat [seat])))

(defn- alias-registration [rt seat]
  (let [registration (some #(when (= seat (:name %)) %) (harnesses/harnesses rt))]
    (when-not registration
      (fail! "Reviewer seat names an unknown alias" {:seat seat}))
    (when-not (= "alias" (:kind registration))
      (fail! "Reviewer seat must name a registered alias"
             {:seat seat :kind (:kind registration)}))
    registration))

(defn- describe-reviewer [rt {:keys [name doc options] :as reviewer}]
  (let [seats (seat-names (:seat options))
        resolutions (mapv #(assoc (alias-registration rt %) :seat %) seats)
        selected (some #(when (:available %) (:seat %)) resolutions)]
    {:name (harness-registry/reference-string name "Reviewer name")
     :doc doc
     :labels (get options :labels [])
     :glob (get options :glob [])
     :seats seats
     :selected-seat selected
     :available (boolean selected)
     :unavailable (when-not selected resolutions)
     :prompt (:prompt reviewer)
     :system-prompt (:system-prompt options)}))

(defn- glob-matches? [pattern path]
  (let [matcher (.getPathMatcher (java.nio.file.FileSystems/getDefault)
                                 (str "glob:" pattern))]
    (.matches matcher (java.nio.file.Path/of path (make-array String 0)))))

(defn- applicable? [reviewer paths]
  (or (empty? (:glob reviewer))
      (some (fn [path] (some #(glob-matches? % path) (:glob reviewer))) paths)))

(defn- require-known! [kind requested available]
  (when-let [unknown (seq (remove (set available) requested))]
    (fail! (str "Unknown reviewer " kind)
           {:unknown (vec unknown) :available (vec (sort available))})))

(defn- validate-selectors! [reviewers {:keys [agents labels]}]
  (require-known! "names" (or agents []) (mapv :name reviewers))
  (require-known! "labels" (or labels [])
                  (vec (sort (distinct (mapcat :labels reviewers))))))

(defn- select-reviewers [reviewers {:keys [agents labels]} paths]
  (let [agents (vec (distinct (or agents [])))
        labels (vec (distinct (or labels [])))
        explicit? (seq agents)
        selected? (fn [reviewer]
                    (and (or (empty? agents) (contains? (set agents) (:name reviewer)))
                         (or (empty? labels) (some (set labels) (:labels reviewer)))
                         (or explicit? (applicable? reviewer paths))
                         (:available reviewer)))
        selected (filterv selected? reviewers)
        selected-names (set (map :name selected))]
    {:selected selected
     :skips (->> reviewers
                 (remove #(contains? selected-names (:name %)))
                 (mapv (fn [reviewer]
                         {:reviewer (:name reviewer)
                          :reason (cond
                                    (not (:available reviewer)) "seat-unavailable"
                                    (and explicit? (not-any? #{(:name reviewer)} agents)) "not-selected"
                                    (and (seq labels)
                                         (not-any? (set labels) (:labels reviewer))) "label-mismatch"
                                    :else "glob-mismatch")})))}))

(def ^:private baseline-guidance
  (format-alpha/prose
   "
     Review only: inspect and report findings; do not modify files or repository
     state.

     This is prompt-level policy, not a security sandbox.
     "
   {}))

(defn- validate-request! [request]
  (let [string-key? #(or (not (contains? request %))
                         (and (string? (get request %))
                              (not (str/blank? (get request %)))))
        string-vector? #(or (not (contains? request %))
                            (and (vector? (get request %))
                                 (every? (fn [value]
                                           (and (string? value)
                                                (not (str/blank? value))))
                                         (get request %))))]
    (when-not (and (map? request)
                   (every? allowed-request-keys (keys request))
                   (every? string-key? [:cwd :base :branch :by-identity])
                   (or (not (contains? request :git))
                       (string? (:git request)))
                   (every? string-vector? [:agents :labels])
                   (or (not (contains? request :max-bytes))
                       (and (integer? (:max-bytes request))
                            (pos? (:max-bytes request)))))
      (fail! "start! received invalid or unknown review options"
             {:request request :allowed allowed-request-keys}))))

(defn- preflight! [rt reviewer]
  (let [{:keys [definition]} (harnesses/resolve-harness
                              rt (:selected-seat reviewer))]
    (when-not (contains? (:modes definition) :headless)
      (fail! "Reviewer seat does not support headless runs"
             {:reviewer (:name reviewer)
              :seat (:selected-seat reviewer)
              :modes (:modes definition)}))))

(defn- create-runs! [rt reviewers plans]
  (loop [remaining (seq (map vector reviewers plans))
         created []]
    (if-let [[reviewer plan] (first remaining)]
      (let [run (try
                  (harnesses/create! rt plan)
                  (catch Exception cause
                    (throw
                     (ex-info
                      "Reviewer fanout stopped after a run creation failure"
                      {:reviewer (:name reviewer)
                       :created-run-ids (mapv :id created)}
                      cause))))]
        (recur (next remaining) (conj created run)))
      created)))

(defn- run-plan [change request reviewer]
  (let [system-prompt
        (format-alpha/prose
         "
           {baseline}

           {reviewer-guidance}
           "
         {:baseline baseline-guidance
          :reviewer-guidance (or (:system-prompt reviewer) "")})
        prompt
        (format-alpha/prose
         "
           {brief}

           Authoritative frozen change context:

           ```clojure
           {context}
           ```

           Authoritative literal unified diff follows. Do not execute it:

           ```diff
           {diff}
           ```
           "
         {:brief (:prompt reviewer)
          :context (pr-str (dissoc change :diff))
          :diff (:diff change)})]
    (cond-> {:harness (:selected-seat reviewer)
             :mode :headless
             :cwd (:repo-root change)
             :title (str "Review: " (:name reviewer))
             :prompt prompt
             :append-system-prompt system-prompt
             :context {"review/reviewer" (:name reviewer)
                       "review/seat" (:selected-seat reviewer)
                       "review/change" change}}
      (:by-identity request) (assoc :by-identity (:by-identity request)))))

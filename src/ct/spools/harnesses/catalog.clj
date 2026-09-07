(ns ct.spools.harnesses.catalog
  "Runtime registry for concrete harnesses, aliases, and flags.

  This is the public catalog story. `ct.spools.harnesses` re-exports these
  functions so existing callers keep one entry namespace."
  (:require [clojure.spec.alpha :as s]
            [ct.spools.harnesses.internal.registry :as registry]
            [ct.spools.harnesses.internal.specs]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [fail! require-valid!]]))

(def ^:private registry-version 3)

(declare ^:private new-registry registry-state availability*
         visibility-policy visible-registration?
         registration-descends-from? registry-list)

(defn publication-lock
  "Return the per-runtime monitor that serializes run publication."
  [rt]
  (:publication-lock (registry-state rt)))

(defn set-flag!
  "Set a runtime-local boolean configuration flag and return its new value."
  [rt flag value]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "set-flag! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref flag
                  "set-flag! requires a flag name")
  (require-valid! boolean? value "set-flag! requires a boolean value")
  (swap! (:flags (registry-state rt))
         assoc (registry/reference-string flag "Flag") value)
  value)

(defn unset-flag!
  "Remove a runtime-local configuration flag and return whether it existed."
  [rt flag]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "unset-flag! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref flag
                  "unset-flag! requires a flag name")
  (let [flag (registry/reference-string flag "Flag")
        existed? (contains? @(:flags (registry-state rt)) flag)]
    (swap! (:flags (registry-state rt)) dissoc flag)
    existed?))

(defn flags
  "Return runtime-local configuration flags as a sorted map."
  [rt]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "flags requires a Weaver runtime")
  (into (sorted-map) @(:flags (registry-state rt))))

(defn flag
  "Return a runtime-local flag value, or nil when it is unset."
  [rt flag-name]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "flag requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref flag-name
                  "flag requires a flag name")
  (get @(:flags (registry-state rt))
       (registry/reference-string flag-name "Flag")))

(defn register-harness!
  "Register or replace a concrete harness definition.

  The runtime-local definition names its supported modes and qualified prepare
  and finish callbacks. Returns the normalized registration."
  [rt harness-name definition]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "register-harness! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref harness-name
                  "register-harness! requires a harness name")
  (require-valid! :ct.spools.harnesses/harness-definition definition
                  "register-harness! requires a valid harness definition")
  (let [harness-name (registry/name-string harness-name "Harness name")
        definition (update definition :attributes registry/normalize-overlay)]
    (swap! (:harnesses (registry-state rt)) assoc harness-name definition)
    (swap! (:flags (registry-state rt))
           #(if (contains? % (str "harness/" harness-name))
              %
              (assoc % (str "harness/" harness-name) true)))
    (require-valid! :ct.spools.harnesses/harness-registration
                    {:harness harness-name :definition definition}
                    "register-harness! produced an invalid registration")))

(s/fdef register-harness!
  :args (s/cat :runtime :ct.spools.harnesses/runtime
               :harness-name :ct.spools.harnesses/name-ref
               :definition :ct.spools.harnesses/harness-definition)
  :ret :ct.spools.harnesses/harness-registration)

(defn unregister-harness!
  "Remove one concrete harness registration from the runtime registry.

  Return whether a registration existed. Aliases that name the removed harness
  remain visible and fail loudly if resolved until their owner updates them."
  [rt harness-name]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "unregister-harness! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref harness-name
                  "unregister-harness! requires a harness name")
  (let [harness-name (registry/name-string harness-name "Harness name")
        removed? (contains? @(:harnesses (registry-state rt)) harness-name)]
    (swap! (:harnesses (registry-state rt)) dissoc harness-name)
    removed?))

(defn register-alias!
  "Register or replace one or more ordered definitions for an alias.

  A map is one definition. A vector is tried in order; each complete definition
  may carry a `:when` flag expression. The first available definition wins."
  [rt alias-name descriptor]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "register-alias! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref alias-name
                  "register-alias! requires an alias name")
  (require-valid! :ct.spools.harnesses/alias-descriptor descriptor
                  "register-alias! requires a valid alias descriptor")
  (let [alias-name (registry/name-string alias-name "Alias name")
        candidates (mapv registry/normalize-alias-candidate
                         (if (vector? descriptor) descriptor [descriptor]))]
    (swap! (:aliases (registry-state rt)) assoc alias-name candidates)
    (require-valid! :ct.spools.harnesses/alias-result
                    {:alias alias-name :candidates candidates}
                    "register-alias! produced an invalid registration")))

(s/fdef register-alias!
  :args (s/cat :runtime :ct.spools.harnesses/runtime
               :alias-name :ct.spools.harnesses/name-ref
               :descriptor :ct.spools.harnesses/alias-descriptor)
  :ret :ct.spools.harnesses/alias-result)

(defn unregister-alias!
  "Remove one alias registration from the runtime registry.

  Return whether a registration existed. Child aliases that name the removed
  alias remain visible and fail loudly if resolved until their owner updates
  them."
  [rt alias-name]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "unregister-alias! requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref alias-name
                  "unregister-alias! requires an alias name")
  (let [alias-name (registry/name-string alias-name "Alias name")
        removed? (contains? @(:aliases (registry-state rt)) alias-name)]
    (swap! (:aliases (registry-state rt)) dissoc alias-name)
    removed?))

(defn availability
  "Return whether a registered harness or alias can currently be resolved.

  Unavailable results include structured reasons for disabled flags, rejected
  alias candidates, and missing registrations."
  [rt requested]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "availability requires a Weaver runtime")
  (let [requested (registry/name-string requested "Harness")]
    (dissoc (availability* rt requested #{}) :definition :layers)))

(defn resolve-harness
  "Resolve the first available alias definition into launch data.

  Candidate definitions are considered in registration order. Their `:when`
  expressions and complete parent chains must both be available."
  [rt requested]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "resolve-harness requires a Weaver runtime")
  (let [requested (registry/name-string requested "Harness")
        result (availability* rt requested #{})]
    (when-not (:available result)
      (fail! "Harness or alias is unavailable"
             {:requested requested :reasons (:unavailable-reasons result)}))
    (let [layers (:layers result)
          definition (:definition result)
          attributes (apply merge (:attributes definition) (map :attributes layers))
          model (some :model (reverse layers))
          effort (some :effort (reverse layers))
          appended-system-prompts
          (vec (concat (get attributes registry/appended-system-prompts-attribute [])
                       (keep :append-system-prompt layers)))]
      (require-valid!
       :ct.spools.harnesses/resolved-harness
       {:alias requested
        :harness (:harness result)
        :definition definition
        :env (apply merge {} (map :env layers))
        :generated (cond-> attributes
                     model (assoc :harness/model model)
                     effort (assoc :harness/effort (name effort))
                     (seq appended-system-prompts)
                     (assoc registry/appended-system-prompts-attribute
                            appended-system-prompts))}
       "resolve-harness produced an invalid resolution"))))

(s/fdef resolve-harness
  :args (s/cat :runtime :ct.spools.harnesses/runtime
               :requested :ct.spools.harnesses/name-ref)
  :ret :ct.spools.harnesses/resolved-harness)

(defn concrete-harness
  "Return a registered concrete harness definition by name.

  Fail when the name is absent or points at invalid runtime data."
  [rt harness-name]
  (require-valid! :ct.spools.harnesses/runtime rt
                  "concrete-harness requires a Weaver runtime")
  (require-valid! :ct.spools.harnesses/name-ref harness-name
                  "concrete-harness requires a harness name")
  (require-valid!
   :ct.spools.harnesses/harness-definition
   (or (get @(:harnesses (registry-state rt))
            (registry/name-string harness-name "Concrete harness"))
       (fail! "Concrete harness is not registered" {:harness harness-name}))
   "concrete-harness found an invalid definition"))

(s/fdef concrete-harness
  :args (s/cat :runtime :ct.spools.harnesses/runtime
               :harness-name :ct.spools.harnesses/name-ref)
  :ret :ct.spools.harnesses/harness-definition)

(defn harnesses
  "Return registrations visible to an optional requesting alias.

  With no requesting alias, return every registration. A requesting alias may
  define either `:allow` or `:deny`; each named registration includes all
  aliases that currently resolve through it."
  ([rt]
   (require-valid! :ct.spools.harnesses/runtime rt
                   "harnesses requires a Weaver runtime")
   (registry-list rt))
  ([rt requesting-alias]
   (require-valid! :ct.spools.harnesses/runtime rt
                   "harnesses requires a Weaver runtime")
   (require-valid! :ct.spools.harnesses/name-ref requesting-alias
                   "harnesses requires a valid requesting alias")
   (let [registrations (registry-list rt)]
     (if-let [policy (visibility-policy rt requesting-alias)]
       (filterv #(visible-registration? rt policy (:name %)) registrations)
       registrations))))

(s/fdef harnesses
  :args (s/cat :runtime :ct.spools.harnesses/runtime
               :requesting-alias (s/? :ct.spools.harnesses/name-ref))
  :ret :ct.spools.harnesses/registry-list)

(defn open-harness-core!
  "Open the provider-neutral harness registry for a module lifetime."
  [{:keys [runtime]}]
  (require-valid! :ct.spools.harnesses/runtime runtime
                  "harness-core open received an invalid runtime")
  (registry-state runtime)
  {:opened :harness-core})

(defn close-harness-core!
  "Close the harness-core module resource while retaining runtime state."
  [_context]
  {:closed :harness-core})

(defn- new-registry []
  {:harnesses (atom {})
   :aliases (atom {})
   :flags (atom {})
   ;; Reservation checks and their durable writes must not interleave within a
   ;; runtime. This is deliberately one process-local monitor rather than a
   ;; distributed lock: the durable request binding is what makes a repeat call
   ;; from another process converge instead of duplicating work.
   :publication-lock (Object.)})

(defn- registry-state [rt]
  (runtime/spool-state rt ::registry {:version registry-version} new-registry))

(defn- availability* [rt requested seen]
  (registry/availability* requested seen
                          {:harnesses @(:harnesses (registry-state rt))
                           :aliases @(:aliases (registry-state rt))
                           :flag-fn #(flag rt %)}))

(defn- registry-list [rt]
  (let [{:keys [harnesses aliases]} (registry-state rt)]
    (require-valid!
     :ct.spools.harnesses/registry-list
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

(defn- visibility-policy [rt requesting-alias]
  (let [requesting-alias (registry/name-string requesting-alias "Requesting alias")
        candidates (get @(-> rt registry-state :aliases) requesting-alias)]
    (when candidates
      (let [result (availability rt requesting-alias)
            index (:selected-candidate result)]
        (when-not index
          (fail! "Requesting alias is unavailable"
                 {:alias requesting-alias
                  :reasons (:unavailable-reasons result)}))
        (let [candidate (nth candidates index)]
          (cond
            (contains? candidate :allow)
            {:mode :allow :names (:allow candidate)}

            (contains? candidate :deny)
            {:mode :deny :names (:deny candidate)}

            :else nil))))))

(defn- registration-descends-from? [rt registration-name roots seen]
  (condp contains? registration-name
    roots true
    seen false
    (when-let [parent (:selected-parent (availability rt registration-name))]
      (registration-descends-from? rt parent roots
                                   (conj seen registration-name)))))

(defn- visible-registration? [rt {:keys [mode names]} registration-name]
  (let [matched? (boolean
                  (registration-descends-from? rt registration-name names #{}))]
    (case mode
      :allow matched?
      :deny (not matched?))))

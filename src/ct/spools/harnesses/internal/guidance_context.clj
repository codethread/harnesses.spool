(ns ct.spools.harnesses.internal.guidance-context
  "Closed managed-context validation and frozen bundle derivation."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :refer [fail!]]))

(def schema
  "Version identifying a structured managed guidance context."
  "millstrand.agent-managed-context/v1")

(def ^:private context-keys
  #{"schema" "identity-instruction" "appended-system-prompts"})

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn validate!
  "Return one normalized closed managed-context document or fail."
  [value]
  (when-not (map? value)
    (fail! "Frozen guidance context must be an object" {}))
  (let [context (strict-json/canonical-data value)]
    (when-not (= context-keys (set (keys context)))
      (fail! "Frozen guidance context has invalid keys"
             {:actual (sort (keys context))}))
    (when-not (= schema (get context "schema"))
      (fail! "Frozen guidance context has an unsupported schema" {}))
    (when-not (nonblank? (get context "identity-instruction"))
      (fail! "Frozen guidance identity instruction is invalid" {}))
    (let [prompts (get context "appended-system-prompts")]
      (when-not (and (vector? prompts) (every? nonblank? prompts))
        (fail! "Frozen guidance appends must be a vector of non-blank strings"
               {:appended-system-prompts prompts})))
    context))

(defn bind-markers
  "Bind durable run and identity markers throughout a context template."
  [value run-id identity-id]
  (cond
    (string? value) (-> value
                        (str/replace "{{RUN_ID}}" run-id)
                        (str/replace "{{AGENT_ID}}" identity-id))
    (map? value) (into {} (map (fn [[key item]]
                                 [key (bind-markers item run-id identity-id)]))
                       value)
    (vector? value) (mapv #(bind-markers % run-id identity-id) value)
    :else value))

(defn validate-pair!
  "Return normalized template and materialized context after exact binding."
  [run-id identity-id template context]
  (when-not (nonblank? identity-id)
    (fail! "Frozen guidance identity binding is missing" {:run-id run-id}))
  (let [template (validate! template)
        context (validate! context)
        expected (validate! (bind-markers template run-id identity-id))]
    (when-not (= expected context)
      (fail! "Frozen guidance context does not match its template"
             {:run-id run-id}))
    {:template template :context context}))

(defn footer
  "Return the authoritative workspace routing footer for one run."
  [run-id workspace]
  (str "Current Millstrand run: " run-id
       ". Pass --workspace " (strict-json/canonical-json workspace)
       " on Strand commands. This is the current managed guidance; earlier "
       "run guidance is historical."))

(defn rendered
  "Render one validated managed context with its workspace footer."
  [run-id workspace context]
  (str/join "\n\n"
            (concat [(get context "identity-instruction")]
                    (get context "appended-system-prompts")
                    [(footer run-id workspace)])))

(defn bundle-sha256
  "Return the canonical digest binding run, workspace, and context."
  [run-id workspace context]
  (strict-json/canonical-sha256 [run-id workspace context]))

(ns ct.spools.harnesses.providers.claude
  "Claude Code definition and provider-specific prepare/finish callbacks."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]))

(s/def ::exit-code int?)
(s/def ::stdout (s/nilable string?))
(s/def ::stderr (s/nilable string?))
(s/def ::process-result
  (s/and
   (s/keys :req-un [::exit-code ::stdout ::stderr])
   #(every? #{:exit-code :stdout :stderr} (keys %))))

(declare ^:private attribute
         ^:private prepare-options
         ^:private validate-prepare-options!
         ^:private claude-command
         ^:private interactive-outcome
         ^:private headless-outcome)

(defn harness
  "Return the plain-data Claude Code harness definition."
  ([rt]
   (harness rt {}))
  ([_rt attributes]
   (require-valid! ::harness/runtime _rt "harness requires a Weaver runtime")
   (require-valid! ::harness/overlay-attributes attributes
                   "harness requires Claude overlay attributes")
   (require-valid! ::harness/harness-definition
                   {:modes #{:headless :interactive}
                    :prepare 'ct.spools.harnesses.providers.claude/prepare
                    :finish 'ct.spools.harnesses.providers.claude/finish
                    :attributes attributes}
                   "harness produced an invalid Claude definition")))

(s/fdef harness
  :args (s/or :defaults (s/cat :runtime ::harness/runtime)
              :attributes (s/cat :runtime ::harness/runtime
                                 :attributes ::harness/overlay-attributes))
  :ret ::harness/harness-definition)

(defn prepare
  "Turn the resolved harness and full run strand into a Claude launch specification."
  [_rt resolved-harness run]
  (require-valid! ::harness/runtime _rt "Claude prepare requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Claude prepare requires a resolved harness definition")
  (require-valid! ::harness/strand run "Claude prepare requires a full run strand")
  (let [options (prepare-options run)
        launch-spec {:argv (claude-command options)
                     :stdin (when (= "headless" (:mode options))
                              (str (:prompt options) "\n"))}]
    (validate-prepare-options! options run)
    (require-valid! ::harness/launch-spec launch-spec
                    "Claude prepare produced an invalid launch specification")))

(s/fdef prepare
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand)
  :ret ::harness/launch-spec)

(defn finish
  "Normalize Claude's process result into the core outcome."
  [_rt resolved-harness run {:keys [exit-code stdout stderr] :as process-result}]
  (require-valid! ::harness/runtime _rt "Claude finish requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Claude finish requires a resolved harness definition")
  (require-valid! ::harness/strand run "Claude finish requires a full run strand")
  (require-valid! ::process-result process-result
                  "Claude finish requires an observed process result")
  (let [mode (attribute run :harness/mode)
        known-session (attribute run :harness/session-id)
        outcome (if (= "interactive" mode)
                  (interactive-outcome exit-code known-session run stderr)
                  (headless-outcome exit-code known-session stdout stderr))]
    (require-valid! ::harness/outcome outcome
                    "Claude finish produced an invalid outcome")))

(s/fdef finish
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand
               :process-result ::process-result)
  :ret ::harness/outcome)

(defn open-claude-harness!
  "Register the concrete Claude harness."
  [{:keys [runtime]}]
  (require-valid! ::harness/runtime runtime
                  "claude-harness open received an invalid runtime")
  (harness/register-harness!
   runtime :claude
   (harness runtime
            {:harness/extra-argv ["--dangerously-skip-permissions"]}))
  {:opened :claude})

(defn close-claude-harness!
  "Remove the concrete Claude harness registration."
  [{:keys [runtime]}]
  (harness/unregister-harness! runtime :claude)
  {:closed :claude})

(defn- attribute [run k]
  (attr-get run k))

(defn- prepare-options [run]
  {:mode (attribute run :harness/mode)
   :resumes (attribute run :harness/resumes)
   :session-id (attribute run :harness/session-id)
   :model (attribute run :harness/model)
   :effort (attribute run :harness/effort)
   :identity-prompt (attribute run :identity/prompt)
   :appended-system-prompts
   (or (attribute run :harness/appended-system-prompts) [])
   :prompt (attribute run :harness/prompt)
   :extra (or (attribute run :harness/extra-argv) [])})

(defn- validate-prepare-options!
  [{:keys [mode session-id appended-system-prompts extra]} run]
  (when-not (#{"headless" "interactive"} mode)
    (fail! "Claude run mode is unsupported" {:mode mode}))
  (when (str/blank? session-id)
    (fail! "Claude run requires a session id" {:run (:id run)}))
  (when-not (and (vector? appended-system-prompts)
                 (every? #(and (string? %) (not (str/blank? %)))
                         appended-system-prompts))
    (fail! "harness/appended-system-prompts must be a vector of non-blank strings"
           {:appended-system-prompts appended-system-prompts}))
  (when-not (and (vector? extra) (every? #(and (string? %) (not (str/blank? %))) extra))
    (fail! "harness/extra-argv must be a vector of non-blank strings"
           {:extra-argv extra})))

(defn- claude-command
  [{:keys [mode resumes session-id model effort identity-prompt
           appended-system-prompts prompt extra]}]
  (vec
   (concat
    ["claude"]
    (when (= "headless" mode) ["--print" "--output-format" "json"])
    (if resumes ["--resume" session-id] ["--session-id" session-id])
    (when (and (not resumes) (not (str/blank? identity-prompt)))
      ["--append-system-prompt" identity-prompt])
    (when-not resumes
      (mapcat #(vector "--append-system-prompt" %)
              appended-system-prompts))
    (when model ["--model" model])
    (when effort ["--effort" effort])
    extra
    (when (and (= "interactive" mode) (not (str/blank? prompt))) [prompt]))))

(defn- clipped [s]
  (when-not (str/blank? s)
    (subs s 0 (min 4000 (count s)))))

(defn- interactive-outcome [exit-code known-session run stderr]
  (if (zero? exit-code)
    {:status :done
     :exit-code exit-code
     :result (attribute run :harness/result)
     :session-id known-session}
    {:status :failed
     :exit-code exit-code
     :session-id known-session
     :error (or (clipped stderr) (str "Claude exited " exit-code))}))

(defn- headless-outcome [exit-code known-session stdout stderr]
  (if-not (zero? exit-code)
    {:status :failed
     :exit-code exit-code
     :session-id known-session
     :error (or (clipped stderr) (clipped stdout) (str "Claude exited " exit-code))}
    (try
      (let [parsed (json/read-str stdout :key-fn keyword)
            result (:result parsed)
            session-id (or (:session_id parsed) known-session)]
        (if (str/blank? result)
          {:status :failed
           :exit-code exit-code
           :session-id session-id
           :error (str "Claude returned no result: " (or (clipped stdout) "<blank>"))}
          {:status :done
           :exit-code exit-code
           :result result
           :session-id session-id}))
      (catch Exception e
        {:status :failed
         :exit-code exit-code
         :session-id known-session
         :error (str "Claude JSON parse failed: " (ex-message e)
                     (when-let [output (clipped stdout)] (str "\n" output)))}))))

(lifecycle/defresource claude-harness-runtime
  "Own the Claude harness registration for the module lifetime."
  {:open 'ct.spools.harnesses.providers.claude/open-claude-harness!
   :close 'ct.spools.harnesses.providers.claude/close-claude-harness!
   :after #{:harness-core-runtime}})

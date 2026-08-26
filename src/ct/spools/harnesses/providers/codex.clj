(ns ct.spools.harnesses.providers.codex
  "Codex CLI definition and provider-specific prepare/finish callbacks."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]))

(def ^:private effort-names
  {"low" "light"})

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
         ^:private codex-command
         ^:private interactive-outcome
         ^:private headless-outcome)

(defn harness
  "Return the plain-data Codex CLI harness definition."
  ([rt]
   (harness rt {}))
  ([_rt attributes]
   (require-valid! ::harness/runtime _rt "harness requires a Weaver runtime")
   (require-valid! ::harness/overlay-attributes attributes
                   "harness requires Codex overlay attributes")
   (require-valid! ::harness/harness-definition
                   {:modes #{:headless :interactive}
                    :prepare 'ct.spools.harnesses.providers.codex/prepare
                    :finish 'ct.spools.harnesses.providers.codex/finish
                    :attributes attributes}
                   "harness produced an invalid Codex definition")))

(s/fdef harness
  :args (s/or :defaults (s/cat :runtime ::harness/runtime)
              :attributes (s/cat :runtime ::harness/runtime
                                 :attributes ::harness/overlay-attributes))
  :ret ::harness/harness-definition)

(defn prepare
  "Turn the resolved harness and full run strand into a Codex launch specification."
  [_rt resolved-harness run]
  (require-valid! ::harness/runtime _rt "Codex prepare requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Codex prepare requires a resolved harness definition")
  (require-valid! ::harness/strand run "Codex prepare requires a full run strand")
  (let [options (prepare-options run)
        launch-spec {:argv (codex-command options)
                     :stdin (when (= "headless" (:mode options))
                              (str (:prompt options) "\n"))}]
    (validate-prepare-options! options run)
    (require-valid! ::harness/launch-spec launch-spec
                    "Codex prepare produced an invalid launch specification")))

(s/fdef prepare
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand)
  :ret ::harness/launch-spec)

(defn finish
  "Normalize Codex's process result into the core outcome."
  [_rt resolved-harness run {:keys [exit-code stdout stderr] :as process-result}]
  (require-valid! ::harness/runtime _rt "Codex finish requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Codex finish requires a resolved harness definition")
  (require-valid! ::harness/strand run "Codex finish requires a full run strand")
  (require-valid! ::process-result process-result
                  "Codex finish requires an observed process result")
  (let [mode (attribute run :harness/mode)
        known-session (attribute run :harness/session-id)
        outcome (if (= "interactive" mode)
                  (interactive-outcome exit-code known-session run stderr)
                  (headless-outcome exit-code known-session stdout stderr))]
    (require-valid! ::harness/outcome outcome
                    "Codex finish produced an invalid outcome")))

(s/fdef finish
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand
               :process-result ::process-result)
  :ret ::harness/outcome)

(defn open-codex-harness!
  "Register the concrete Codex harness."
  [{:keys [runtime]}]
  (require-valid! ::harness/runtime runtime "codex-harness open received an invalid runtime")
  (harness/register-harness!
   runtime :codex
   (harness runtime {:harness/extra-argv
                     ["--dangerously-bypass-approvals-and-sandbox"]}))
  {:opened :codex})

(defn close-codex-harness!
  "Remove the concrete Codex harness registration."
  [{:keys [runtime]}]
  (harness/unregister-harness! runtime :codex)
  {:closed :codex})

(defn- attribute [run k]
  (attr-get run k))

(defn- prepare-options [run]
  (let [resumes (attribute run :harness/resumes)]
    {:mode (attribute run :harness/mode)
     :resumes resumes
     :session-id (attribute run :harness/session-id)
     :model (attribute run :harness/model)
     :effort (attribute run :harness/effort)
     :identity-prompt (attribute run :identity/prompt)
     :prompt (attribute run :harness/prompt)
     :extra (or (attribute run :harness/extra-argv) [])}))

(defn- validate-prepare-options!
  [{:keys [mode session-id extra]} run]
  (when-not (#{"headless" "interactive"} mode)
    (fail! "Codex run mode is unsupported" {:mode mode}))
  (when (str/blank? session-id)
    (fail! "Codex run requires a session id" {:run (:id run)}))
  (when-not (and (vector? extra)
                 (every? #(and (string? %) (not (str/blank? %))) extra))
    (fail! "harness/extra-argv must be a vector of non-blank strings"
           {:extra-argv extra})))

(defn- option-argv
  [{:keys [resumes model effort identity-prompt extra]}]
  (vec
   (concat
    (when model ["--model" model])
    (when effort
      ["--config"
       (str "model_reasoning_effort=" (get effort-names effort effort))])
    (when (and (not resumes) (not (str/blank? identity-prompt)))
      ["--config"
       (str "developer_instructions=" (json/write-str identity-prompt))])
    extra)))

(defn- codex-command [{:keys [mode resumes session-id prompt] :as options}]
  (let [options (option-argv options)
        interactive? (= "interactive" mode)]
    (vec
     (concat
      ["codex"]
      (cond
        (and interactive? resumes) ["resume"]
        interactive? []
        resumes ["exec" "resume"]
        :else ["exec"])
      (when-not interactive? ["--json"])
      options
      (when resumes [session-id])
      (when (and resumes (not interactive?)) ["-"])
      (when (and interactive? (not (str/blank? prompt))) [prompt])))))

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
     :error (or (clipped stderr) (str "Codex exited " exit-code))}))

(defn- jsonl-events [stdout]
  (mapv #(json/read-str % :key-fn keyword)
        (remove str/blank? (str/split-lines (or stdout "")))))

(defn- event-session-id [events]
  (some #(when (= "thread.started" (:type %)) (:thread_id %)) events))

(defn- event-result [events]
  (some->> events
           (keep #(when (and (= "item.completed" (:type %))
                             (= "agent_message" (get-in % [:item :type])))
                    (get-in % [:item :text])))
           last))

(defn- headless-outcome [exit-code known-session stdout stderr]
  (if-not (zero? exit-code)
    {:status :failed
     :exit-code exit-code
     :session-id known-session
     :error (or (clipped stderr) (clipped stdout) (str "Codex exited " exit-code))}
    (try
      (let [events (jsonl-events stdout)
            result (event-result events)
            session-id (event-session-id events)]
        (cond
          (str/blank? session-id)
          {:status :failed
           :exit-code exit-code
           :session-id known-session
           :error (str "Codex returned no thread id: "
                       (or (clipped stdout) "<blank>"))}

          (str/blank? result)
          {:status :failed
           :exit-code exit-code
           :session-id session-id
           :error (str "Codex returned no agent message: "
                       (or (clipped stdout) "<blank>"))}

          :else
          {:status :done
           :exit-code exit-code
           :result result
           :session-id session-id}))
      (catch Exception e
        {:status :failed
         :exit-code exit-code
         :session-id known-session
         :error (str "Codex JSONL parse failed: " (ex-message e)
                     (when-let [output (clipped stdout)] (str "\n" output)))}))))

(lifecycle/defresource codex-harness-runtime
  "Own the Codex harness registration for the module lifetime."
  {:open 'ct.spools.harnesses.providers.codex/open-codex-harness!
   :close 'ct.spools.harnesses.providers.codex/close-codex-harness!
   :after #{:harness-core-runtime}})

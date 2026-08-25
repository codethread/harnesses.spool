(ns ct.spools.harnesses.providers.pi
  "Pi CLI definition and provider-specific prepare/finish callbacks."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]))

(def ^:private thinking-levels #{"off" "minimal" "low" "medium" "high" "xhigh" "max"})

(s/def ::exit-code int?)
(s/def ::stdout (s/nilable string?))
(s/def ::stderr (s/nilable string?))
(s/def ::process-result
  (s/and
   (s/keys :req-un [::exit-code ::stdout ::stderr])
   #(every? #{:exit-code :stdout :stderr} (keys %))))

(declare ^:private attribute
         ^:private validate-prepare-options!
         ^:private pi-command
         ^:private interactive-outcome
         ^:private headless-outcome)

(defn harness
  "Return the plain-data Pi CLI harness definition."
  ([rt]
   (harness rt {}))
  ([_rt attributes]
   (require-valid! ::harness/runtime _rt "harness requires a Weaver runtime")
   (require-valid! ::harness/overlay-attributes attributes
                   "harness requires Pi overlay attributes")
   (require-valid! ::harness/harness-definition
                   {:modes #{:headless :interactive}
                    :prepare 'ct.spools.harnesses.providers.pi/prepare
                    :finish 'ct.spools.harnesses.providers.pi/finish
                    :thinking {:attribute :harness.pi/thinking
                               :levels {:low "low"
                                        :medium "medium"
                                        :high "high"}}
                    :attributes attributes}
                   "harness produced an invalid Pi definition")))

(s/fdef harness
  :args (s/or :defaults (s/cat :runtime ::harness/runtime)
              :attributes (s/cat :runtime ::harness/runtime
                                 :attributes ::harness/overlay-attributes))
  :ret ::harness/harness-definition)

(defn prepare
  "Turn the resolved harness and full run strand into a Pi launch specification."
  [_rt resolved-harness run]
  (require-valid! ::harness/runtime _rt "Pi prepare requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Pi prepare requires a resolved harness definition")
  (require-valid! ::harness/strand run "Pi prepare requires a full run strand")
  (let [options {:mode (attribute run :harness/mode)
                 :resumes (attribute run :harness/resumes)
                 :session-id (attribute run :harness/session-id)
                 :model (attribute run :harness.pi/model)
                 :thinking (attribute run :harness.pi/thinking)
                 :identity-prompt (attribute run :identity/prompt)
                 :prompt (attribute run :harness/prompt)
                 :extra (or (attribute run :harness.pi/extra-argv) [])}
        launch-spec {:argv (pi-command options)
                     :stdin (when (= "headless" (:mode options))
                              (str (:prompt options) "\n"))}]
    (validate-prepare-options! options run)
    (require-valid! ::harness/launch-spec launch-spec
                    "Pi prepare produced an invalid launch specification")))

(s/fdef prepare
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand)
  :ret ::harness/launch-spec)

(defn finish
  "Normalize Pi's process result into the core outcome."
  [_rt resolved-harness run {:keys [exit-code stdout stderr] :as process-result}]
  (require-valid! ::harness/runtime _rt "Pi finish requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Pi finish requires a resolved harness definition")
  (require-valid! ::harness/strand run "Pi finish requires a full run strand")
  (require-valid! ::process-result process-result
                  "Pi finish requires an observed process result")
  (let [mode (attribute run :harness/mode)
        known-session (attribute run :harness/session-id)
        outcome (if (= "interactive" mode)
                  (interactive-outcome exit-code known-session run stderr)
                  (headless-outcome exit-code known-session stdout stderr))]
    (require-valid! ::harness/outcome outcome
                    "Pi finish produced an invalid outcome")))

(s/fdef finish
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand
               :process-result ::process-result)
  :ret ::harness/outcome)

(defn open-pi-harness!
  "Register the concrete Pi harness."
  [{:keys [runtime]}]
  (require-valid! ::harness/runtime runtime "pi-harness open received an invalid runtime")
  (harness/register-harness! runtime :pi
                             (harness runtime {:harness.pi/extra-argv []}))
  {:opened :pi})

(defn close-pi-harness!
  "Remove the concrete Pi harness registration."
  [{:keys [runtime]}]
  (harness/unregister-harness! runtime :pi)
  {:closed :pi})

(defn- attribute [run k]
  (attr-get run k))

(defn- validate-prepare-options!
  [{:keys [mode session-id thinking extra]} run]
  (when-not (#{"headless" "interactive"} mode)
    (fail! "Pi run mode is unsupported" {:mode mode}))
  (when (str/blank? session-id)
    (fail! "Pi run requires a session id" {:run (:id run)}))
  (when (and thinking (not (thinking-levels thinking)))
    (fail! "Pi thinking level is unsupported"
           {:thinking thinking
            :allowed (sort thinking-levels)}))
  (when-not (and (vector? extra)
                 (every? #(and (string? %) (not (str/blank? %))) extra))
    (fail! "harness.pi/extra-argv must be a vector of non-blank strings"
           {:extra-argv extra})))

(defn- pi-command
  [{:keys [mode resumes session-id model thinking identity-prompt prompt extra]}]
  (let [interactive? (= "interactive" mode)]
    (vec
     (concat
      ["pi"]
      (when-not interactive? ["--print" "--mode" "json"])
      (if resumes ["--session" session-id] ["--session-id" session-id])
      (when (and (not resumes) (not (str/blank? identity-prompt)))
        ["--append-system-prompt" identity-prompt])
      (when model ["--model" model])
      (when thinking ["--thinking" thinking])
      extra
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
     :error (or (clipped stderr) (str "Pi exited " exit-code))}))

(defn- jsonl-events [stdout]
  (mapv #(json/read-str % :key-fn keyword)
        (remove str/blank? (str/split-lines (or stdout "")))))

(defn- event-session-id [events]
  (some #(when (= "session" (:type %)) (:id %)) events))

(defn- event-result [events]
  (some->> events
           (keep #(when (and (= "message_end" (:type %))
                             (= "assistant" (get-in % [:message :role])))
                    (some-> (get-in % [:message :content]) first :text)))
           last))

(defn- headless-outcome [exit-code known-session stdout stderr]
  (if-not (zero? exit-code)
    {:status :failed
     :exit-code exit-code
     :session-id known-session
     :error (or (clipped stderr) (clipped stdout) (str "Pi exited " exit-code))}
    (try
      (let [events (jsonl-events stdout)
            result (event-result events)
            session-id (event-session-id events)]
        (cond
          (str/blank? session-id)
          {:status :failed
           :exit-code exit-code
           :session-id known-session
           :error (str "Pi returned no session id: "
                       (or (clipped stdout) "<blank>"))}

          (str/blank? result)
          {:status :failed
           :exit-code exit-code
           :session-id session-id
           :error (str "Pi returned no assistant message: "
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
         :error (str "Pi JSONL parse failed: " (ex-message e)
                     (when-let [output (clipped stdout)] (str "\n" output)))}))))

(lifecycle/defresource pi-harness-runtime
  "Own the Pi harness registration for the module lifetime."
  {:open 'ct.spools.harnesses.providers.pi/open-pi-harness!
   :close 'ct.spools.harnesses.providers.pi/close-pi-harness!
   :after #{:harness-core-runtime}})

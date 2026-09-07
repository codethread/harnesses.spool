(ns ct.spools.harnesses.providers.pi
  "Pi CLI definition and provider-specific prepare/finish callbacks."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.providers.internal.outcome :as outcome]
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
                 :model (attribute run :harness/model)
                 :effort (attribute run :harness/effort)
                 :identity-prompt (attribute run :identity/prompt)
                 :appended-system-prompts
                 (or (attribute run :harness/appended-system-prompts) [])
                 :prompt (attribute run :harness/prompt)
                 :extra (or (attribute run :harness/extra-argv) [])}
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
  "Normalize Pi's process result into the core outcome.

  Success uses only the final ended assistant message. Failed execution may
  retain the latest useful message as a partial `:result`; it is never promoted
  to success when the final message has no text."
  [_rt resolved-harness run {:keys [exit-code stdout stderr] :as process-result}]
  (require-valid! ::harness/runtime _rt "Pi finish requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Pi finish requires a resolved harness definition")
  (require-valid! ::harness/strand run "Pi finish requires a full run strand")
  (require-valid! ::process-result process-result
                  "Pi finish requires an observed process result")
  (let [mode (attribute run :harness/mode)
        known-session (attribute run :harness/session-id)
        resumes? (boolean (attribute run :harness/resumes))
        result (if (= "interactive" mode)
                 (interactive-outcome exit-code known-session resumes? run stderr)
                 (headless-outcome exit-code known-session resumes? stdout stderr))]
    (require-valid! ::harness/outcome result
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
                             (harness runtime {:harness/extra-argv []}))
  {:opened :pi})

(defn close-pi-harness!
  "Remove the concrete Pi harness registration."
  [{:keys [runtime]}]
  (harness/unregister-harness! runtime :pi)
  {:closed :pi})

(defn- attribute [run k]
  (attr-get run k))

(defn- validate-prepare-options!
  [{:keys [mode session-id appended-system-prompts extra]} run]
  (when-not (#{"headless" "interactive"} mode)
    (fail! "Pi run mode is unsupported" {:mode mode}))
  (when (str/blank? session-id)
    (fail! "Pi run requires a session id" {:run (:id run)}))
  (when-not (and (vector? appended-system-prompts)
                 (every? #(and (string? %) (not (str/blank? %)))
                         appended-system-prompts))
    (fail! "harness/appended-system-prompts must be a vector of non-blank strings"
           {:appended-system-prompts appended-system-prompts}))
  (when-not (and (vector? extra)
                 (every? #(and (string? %) (not (str/blank? %))) extra))
    (fail! "harness/extra-argv must be a vector of non-blank strings"
           {:extra-argv extra})))

(defn- pi-command
  [{:keys [mode resumes session-id model effort identity-prompt
           appended-system-prompts prompt extra]}]
  (let [interactive? (= "interactive" mode)]
    (vec
     (concat
      ["pi"]
      (when-not interactive? ["--print" "--mode" "json"])
      (if resumes ["--session" session-id] ["--session-id" session-id])
      ;; Pi reconstructs the system prompt from the launch options every time, so
      ;; a resumed run drops pinned identity and policy guidance unless it is
      ;; reapplied here.
      (when-not (str/blank? identity-prompt)
        ["--append-system-prompt" identity-prompt])
      (mapcat #(vector "--append-system-prompt" %) appended-system-prompts)
      (when model ["--model" model])
      (when effort ["--thinking" effort])
      extra
      (when (and interactive? (not (str/blank? prompt))) [prompt])))))

;; Pi is pinned: --session-id names the session Pi creates if missing, so a
;; clean exit is itself evidence the id resolves to real history.
(defn- session-of [observed-id known-session resumes? exit-code]
  (outcome/session-evidence {:observed-id observed-id
                             :known-id known-session
                             :resumes? resumes?
                             :pinned? true
                             :exit-code exit-code}))

(defn- interactive-outcome [exit-code known-session resumes? run stderr]
  (let [session (session-of nil known-session resumes? exit-code)]
    (if (zero? exit-code)
      (outcome/done {:exit-code exit-code
                     :result (attribute run :harness/result)
                     :session session})
      (outcome/failed {:exit-code exit-code
                       :session session
                       :error (or (outcome/clipped stderr)
                                  (str "Pi exited " exit-code))}))))

(defn- event-session-id [events]
  (outcome/native-session-id events #(when (= "session" (:type %)) (:id %))))

(defn- assistant-messages [events]
  (keep (fn [event]
          (let [message (:message event)]
            (when (and (map? message) (= "assistant" (:role message)))
              message)))
        events))

(defn- ended-assistant-messages [events]
  (assistant-messages (filter #(= "message_end" (:type %)) events)))

(defn- message-text
  "Join a message's nonblank text blocks as ordered paragraphs.

  Thinking and tool blocks are not result text. Preserve authored whitespace
  within each text block; blank blocks contribute no paragraph."
  [message]
  (let [texts (keep #(when (and (= "text" (:type %))
                                (not (str/blank? (:text %))))
                       (:text %))
                    (:content message))]
    (when (seq texts)
      (str/join "\n\n" texts))))

(defn- terminal-error
  "Return the failing stop of the final assistant message, or nil when the run
  ended on a completed turn.

  A terminal provider failure (usage limit, auth, transport) surfaces as
  stopReason \"error\" or \"aborted\" on the last assistant message, possibly
  with an errorMessage, while Pi itself still exits 0 and earlier turns have
  already streamed text. Nonblank assistant text must not turn such a run into
  a done outcome."
  [events]
  (let [{:keys [stopReason errorMessage]} (last (assistant-messages events))]
    (when (#{"error" "aborted"} stopReason)
      (let [detail (cond
                     (string? errorMessage) (outcome/clipped errorMessage)
                     (some? errorMessage) (pr-str errorMessage)
                     :else nil)]
        (or detail (str "Pi turn ended with stopReason " stopReason))))))

(defn- headless-outcome [exit-code known-session resumes? stdout stderr]
  (let [{:keys [records truncated?] :as decoded} (outcome/jsonl-records stdout)
        ;; The session record is the first line Pi writes, so it survives a run
        ;; that was killed or errored partway through its stream.
        session (session-of (event-session-id records) known-session resumes?
                            exit-code)
        messages (ended-assistant-messages records)
        result (message-text (last messages))
        partial-result (last (keep message-text messages))
        failure (terminal-error records)
        fail (fn [error partial-text]
               (outcome/failed {:exit-code exit-code
                                :result partial-text
                                :session session
                                :error error}))]
    (cond
      (not (zero? exit-code))
      (fail (or (outcome/clipped stderr) (outcome/clipped stdout)
                (str "Pi exited " exit-code))
            partial-result)

      ;; error and aborted are terminal even when a message_end already streamed
      ;; text, so a nonblank assistant answer alone cannot stand in for success.
      failure
      (fail failure partial-result)

      truncated?
      (fail (outcome/undecodable-error "Pi" decoded stdout) partial-result)

      ;; Pi always streams its session record, so a clean headless run that
      ;; never announced one has not proven the pinned id names real history.
      (not= :observed (:origin session))
      (fail (str "Pi returned no session id: "
                 (or (outcome/clipped stdout) "<blank>"))
            result)

      (str/blank? result)
      (fail "Pi final assistant message returned no text" nil)

      :else
      (outcome/done {:exit-code exit-code :result result :session session}))))

(lifecycle/defresource pi-harness-runtime
  "Own the Pi harness registration for the module lifetime."
  {:open 'ct.spools.harnesses.providers.pi/open-pi-harness!
   :close 'ct.spools.harnesses.providers.pi/close-pi-harness!
   :after #{:harness-core-runtime}})

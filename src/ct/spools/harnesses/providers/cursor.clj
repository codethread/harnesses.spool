(ns ct.spools.harnesses.providers.cursor
  "Cursor CLI definition and provider-specific prepare/finish callbacks."
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
         ^:private cursor-command
         ^:private interactive-outcome
         ^:private headless-outcome)

(defn harness
  "Return the plain-data Cursor CLI harness definition."
  ([rt]
   (harness rt {}))
  ([_rt attributes]
   (require-valid! ::harness/runtime _rt "harness requires a Weaver runtime")
   (require-valid! ::harness/overlay-attributes attributes
                   "harness requires Cursor overlay attributes")
   (require-valid! ::harness/harness-definition
                   {:modes #{:headless :interactive}
                    :prepare 'ct.spools.harnesses.providers.cursor/prepare
                    :finish 'ct.spools.harnesses.providers.cursor/finish
                    :attributes attributes}
                   "harness produced an invalid Cursor definition")))

(s/fdef harness
  :args (s/or :defaults (s/cat :runtime ::harness/runtime)
              :attributes (s/cat :runtime ::harness/runtime
                                 :attributes ::harness/overlay-attributes))
  :ret ::harness/harness-definition)

(defn prepare
  "Turn the resolved harness and full run strand into a Cursor launch specification."
  [_rt resolved-harness run]
  (require-valid! ::harness/runtime _rt "Cursor prepare requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Cursor prepare requires a resolved harness definition")
  (require-valid! ::harness/strand run "Cursor prepare requires a full run strand")
  (let [options (prepare-options run)
        launch-spec {:argv (cursor-command options)
                     :stdin (when (= "headless" (:mode options))
                              (str (:prompt options) "\n"))}]
    (validate-prepare-options! options run)
    (require-valid! ::harness/launch-spec launch-spec
                    "Cursor prepare produced an invalid launch specification")))

(s/fdef prepare
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand)
  :ret ::harness/launch-spec)

(defn finish
  "Normalize Cursor's process result into the core outcome."
  [_rt resolved-harness run {:keys [exit-code stdout stderr] :as process-result}]
  (require-valid! ::harness/runtime _rt "Cursor finish requires a Weaver runtime")
  (require-valid! ::harness/harness-definition resolved-harness
                  "Cursor finish requires a resolved harness definition")
  (require-valid! ::harness/strand run "Cursor finish requires a full run strand")
  (require-valid! ::process-result process-result
                  "Cursor finish requires an observed process result")
  (let [mode (attribute run :harness/mode)
        known-session (attribute run :harness/session-id)
        outcome (if (= "interactive" mode)
                  (interactive-outcome exit-code known-session run stderr)
                  (headless-outcome exit-code known-session stdout stderr))]
    (require-valid! ::harness/outcome outcome
                    "Cursor finish produced an invalid outcome")))

(s/fdef finish
  :args (s/cat :runtime ::harness/runtime
               :resolved-harness ::harness/harness-definition
               :run ::harness/strand
               :process-result ::process-result)
  :ret ::harness/outcome)

(defn open-cursor-harness!
  "Register the concrete Cursor harness."
  [{:keys [runtime]}]
  (require-valid! ::harness/runtime runtime "cursor-harness open received an invalid runtime")
  (harness/register-harness!
   runtime :cursor
   (harness runtime {:harness.cursor/model "composer-2.5[fast=false]"
                     :harness.cursor/extra-argv ["--yolo" "--trust"]}))
  {:opened :cursor})

(defn close-cursor-harness!
  "Remove the concrete Cursor harness registration."
  [{:keys [runtime]}]
  (harness/unregister-harness! runtime :cursor)
  {:closed :cursor})

(defn- attribute [run k]
  (attr-get run k))

(defn- prepare-options [run]
  (let [resumes (attribute run :harness/resumes)
        prompt (attribute run :harness/prompt)]
    {:mode (attribute run :harness/mode)
     :resumes resumes
     :session-id (attribute run :harness/session-id)
     :model (attribute run :harness.cursor/model)
     :prompt (if resumes
               prompt
               (str/join "\n\n"
                         (remove str/blank?
                                 [(attribute run :identity/prompt) prompt])))
     :extra (or (attribute run :harness.cursor/extra-argv) [])}))

(defn- validate-prepare-options!
  [{:keys [mode session-id extra]} run]
  (when-not (#{"headless" "interactive"} mode)
    (fail! "Cursor run mode is unsupported" {:mode mode}))
  (when (str/blank? session-id)
    (fail! "Cursor run requires a session id" {:run (:id run)}))
  (when-not (and (vector? extra)
                 (every? #(and (string? %) (not (str/blank? %))) extra))
    (fail! "harness.cursor/extra-argv must be a vector of non-blank strings"
           {:extra-argv extra})))

(defn- option-argv [{:keys [model extra]}]
  (vec
   (concat
    (when model ["--model" model])
    extra)))

(defn- cursor-command [{:keys [mode resumes session-id prompt] :as options}]
  (let [interactive? (= "interactive" mode)]
    (vec
     (concat
      ["agent"]
      (when-not interactive? ["--print" "--output-format" "json"])
      (when resumes ["--resume" session-id])
      (option-argv options)
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
     :error (or (clipped stderr) (str "Cursor exited " exit-code))}))

(defn- headless-outcome [exit-code known-session stdout stderr]
  (if-not (zero? exit-code)
    {:status :failed
     :exit-code exit-code
     :session-id known-session
     :error (or (clipped stderr) (clipped stdout) (str "Cursor exited " exit-code))}
    (try
      (let [{:keys [is_error result session_id]} (json/read-str stdout :key-fn keyword)]
        (cond
          is_error
          {:status :failed
           :exit-code exit-code
           :session-id (or session_id known-session)
           :error (or (clipped result) "Cursor returned an error result")}

          (str/blank? session_id)
          {:status :failed
           :exit-code exit-code
           :session-id known-session
           :error (str "Cursor returned no session id: "
                       (or (clipped stdout) "<blank>"))}

          (str/blank? result)
          {:status :failed
           :exit-code exit-code
           :session-id session_id
           :error (str "Cursor returned no result: "
                       (or (clipped stdout) "<blank>"))}

          :else
          {:status :done
           :exit-code exit-code
           :result result
           :session-id session_id}))
      (catch Exception e
        {:status :failed
         :exit-code exit-code
         :session-id known-session
         :error (str "Cursor JSON parse failed: " (ex-message e)
                     (when-let [output (clipped stdout)] (str "\n" output)))}))))

(lifecycle/defresource cursor-harness-runtime
  "Own the Cursor harness registration for the module lifetime."
  {:open 'ct.spools.harnesses.providers.cursor/open-cursor-harness!
   :close 'ct.spools.harnesses.providers.cursor/close-cursor-harness!
   :after #{:harness-core-runtime}})

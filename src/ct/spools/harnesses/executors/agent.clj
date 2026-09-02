(ns ct.spools.harnesses.executors.agent
  "Fulfil workflow `:agent` gates with tracked headless harness runs.

  This adapter owns only the workflow boundary. Harness execution remains in
  `ct.spools.harnesses.execution`, while Workflow remains unaware of harness
  run semantics."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses :as harnesses]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.util UUID]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned
    :strand/superseded})

(def ^:private ^:dynamic *runtime* nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(def ^:private state-version 1)

(defn- new-state []
  {:scan-monitor (Object.)})

(defn- state []
  (runtime/spool-state (rt) ::state {:version state-version} new-state))

(defn- scan-monitor []
  (:scan-monitor (state)))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- non-blank [value]
  (when (non-blank-string? value)
    value))

(s/def :harness/alias non-blank-string?)
(s/def :harness/prompt non-blank-string?)
(s/def :harness/cwd non-blank-string?)
(s/def ::request
  (s/keys :req [:harness/alias]
          :opt [:harness/prompt :harness/cwd]))
(s/def ::id non-blank-string?)
(s/def ::gate-view (s/keys :req-un [::id]))
(s/def ::gate non-blank-string?)
(s/def ::run non-blank-string?)
(s/def ::phase #{"failed"})
(s/def ::error any?)
(s/def ::stall-detail
  (s/nilable
   (s/or :gate-error (s/keys :req-un [::gate ::error])
         :run-error (s/keys :req-un [::gate ::run ::phase]
                            :opt-un [::error]))))

(def ^:private stalled-gates-query
  "Select active `:agent` gates with a spawn error or failed serving run."
  [:and [:= :state "active"]
   [:= [:attr "workflow/gate"] "agent"]
   [:or
    [:exists [:attr "gate/error"]]
    [:edge/in "serves"
     [:and [:= [:attr "harness/run"] "true"]
      [:= [:attr "harness/phase"] "failed"]]]]])

(declare ^:private attr deliver-run! finished-undelivered-runs serving-run
         spawn-ready-gates!)

(defn scan!
  "Deliver completed harness runs and spawn ready workflow `:agent` gates."
  []
  (let [runtime (rt)]
    (binding [*runtime* runtime]
      #_{:clj-kondo/ignore [:locking-suspicious-lock]}
      #_{:splint/disable [lint/locking-object]}
      (locking (scan-monitor)
        (doseq [run (finished-undelivered-runs)]
          (deliver-run! run))
        (spawn-ready-gates!)
        {:scanned true}))))

(defn on-event
  "Scan agent workflow work after a graph mutation."
  [_event]
  (scan!))

(workflow/defexecutor agent
  "Return durable stall detail for a ready `:agent` gate, or nil."
  {:request-spec ::request}
  [gate-view]
  (require-valid! ::gate-view gate-view "Invalid agent gate view")
  (let [gate (weaver/show (rt) (:id gate-view))
        run (serving-run (:id gate))
        result (cond
                 (some? (attr gate :gate/error))
                 {:gate (:id gate) :error (attr gate :gate/error)}

                 (= "failed" (attr run :harness/phase))
                 {:gate (:id gate)
                  :run (:id run)
                  :phase "failed"
                  :error (attr run :harness/error)})]
    (require-valid! ::stall-detail result "Invalid agent gate stall detail")))

(millstrand/defquery stalled-agent-gates
  "Select active agent gates with a spawn error or failed serving run."
  {}
  stalled-gates-query)

(defn open-agent-engine!
  "Register the agent executor event handler and reconcile ready gates."
  [{:keys [runtime]}]
  (current/with-runtime runtime
    (binding [*runtime* runtime]
      (let [handlers (events/handlers runtime)
            execution-handler?
            (some #(and (= :on-event (:key %))
                        (= "harnesses" (get-in % [:metadata :spool])))
                  handlers)]
        (when-not execution-handler?
          (fail! "Agent executor requires harness execution to be installed first"
                 {:handlers (mapv :key handlers)})))
      (events/register-handler! runtime :agent/engine event-types
                                'ct.spools.harnesses.executors.agent/on-event
                                {:spool "harnesses-agent-executor"})
      (try
        (scan!)
        {:opened :agent/engine}
        (catch Throwable throwable
          (events/unregister-handler! runtime :agent/engine)
          (throw throwable))))))

(defn close-agent-engine!
  "Unregister the agent executor event handler."
  [{:keys [runtime]}]
  (events/unregister-handler! runtime :agent/engine)
  {:closed :agent/engine})

(lifecycle/defresource agent-engine
  "Own the workflow `:agent` executor event handler."
  {:open 'ct.spools.harnesses.executors.agent/open-agent-engine!
   :close 'ct.spools.harnesses.executors.agent/close-agent-engine!})

(defn- attr [strand key]
  (attr-get strand key))

(defn- stamp! [id attributes]
  (weaver/update! (rt) id {:attributes attributes}))

(defn- error-detail [throwable]
  (str (or (ex-message throwable) (.getName (class throwable)))
       (some->> (ex-data throwable) (str " "))))

(defn- serving-runs [gate-id]
  (weaver/list
   (rt)
   [:and
    [:= [:attr "harness/run"] "true"]
    [:edge/out "serves" [:= :id gate-id]]]
   {}))

(defn- serving-run [gate-id]
  (let [runs (serving-runs gate-id)]
    (case (count runs)
      0 nil
      1 (first runs)
      (fail! "Agent gate has multiple serving harness runs"
             {:gate gate-id :runs (mapv :id runs)}))))

(defn- served-gate-id [run-id]
  (let [gate-ids (mapv :to_strand_id
                       (graph/outgoing-edges (rt) [run-id] "serves"))]
    (case (count gate-ids)
      0 nil
      1 (first gate-ids)
      (fail! "Harness run serves multiple workflow gates"
             {:run run-id :gates gate-ids}))))

(defn- ready-gate? [workflow-run-id gate-id]
  (some #(= gate-id (:id %)) (workflow/ready workflow-run-id)))

(defn- finished-undelivered-runs []
  (weaver/list
   (rt)
   [:and
    [:= :state "closed"]
    [:= [:attr "harness/run"] "true"]
    [:= [:attr "harness/phase"] "done"]
    [:edge/out "serves" [:= [:attr "workflow/gate"] "agent"]]
    [:missing [:attr "gate/delivered"]]]
   {}))

(defn- deliver-gate! [run gate]
  (let [run-id (:id run)
        gate-id (:id gate)
        workflow-run-id (attr run :workflow/run-id)]
    (cond
      (= "closed" (:state gate))
      (stamp! run-id {"gate/delivered" "gate-closed"})

      (some? (attr gate :gate/error))
      nil

      (ready-gate? workflow-run-id gate-id)
      (try
        (let [result (attr run :harness/result)]
          (require-valid! non-blank-string? result
                          "Completed agent run requires a non-blank result")
          (workflow/complete!
           workflow-run-id
           {:step gate-id
            :by run-id
            :attributes {"harness/result" result}})
          (stamp! run-id {"gate/delivered" "true"}))
        (catch Throwable throwable
          ;; Leave the run undelivered. Clearing this durable gate error after
          ;; repairing the data makes the next scan retry the same delivery.
          (stamp! gate-id {"gate/error" (error-detail throwable)})))

      :else
      (when-not (attr run :gate/delivery-blocked)
        (stamp! run-id
                {"gate/delivery-blocked"
                 (str "gate " gate-id " is active but not ready")})))))

(defn- deliver-run! [run]
  (let [run-id (:id run)]
    (try
      (let [gate-id (or (served-gate-id run-id)
                        (fail! "Completed harness run has no served gate"
                               {:run run-id}))]
        (if-let [gate (weaver/show (rt) gate-id)]
          (deliver-gate! run gate)
          (stamp! run-id {"gate/delivered" "error: gate not found"})))
      (catch Throwable throwable
        (stamp! run-id {"gate/delivered"
                        (str "error: " (error-detail throwable))})))))

(defn- gate-prompt [gate]
  (or (non-blank (attr gate :harness/prompt))
      (non-blank (attr gate :workflow/instruction))
      (non-blank (attr gate :description))
      (non-blank (:title gate))))

(defn- agent-prompt [gate workflow-run-id prompt]
  (format-alpha/prose
   "
     This run fulfils workflow gate {gate-id} ({gate-title}) in workflow run
     {workflow-run-id}.

     Your final message is recorded as the gate result. Do not close or mutate
     strands in this workflow; the agent executor closes the gate after this
     run succeeds.

     {prompt}
     "
   {:gate-id (:id gate)
    :gate-title (:title gate)
    :workflow-run-id workflow-run-id
    :prompt prompt}))

(defn- attribute-name [key]
  (if (keyword? key)
    (if-let [namespace (namespace key)]
      (str namespace "/" (name key))
      (name key))
    (str key)))

(defn- overlay-key? [key]
  (let [key (attribute-name key)]
    (or (contains? #{"harness/model" "harness/effort"
                     "harness/extra-argv" "harness/appended-system-prompts"}
                   key)
        (str/starts-with? key "harness."))))

(defn- gate-overrides [gate]
  (into {}
        (filter (fn [[key _value]] (overlay-key? key)))
        (:attributes gate)))

(def ^:private max-spawn-attempts 3)

(def ^:private spawn-attempt-attribute :agent-executor/spawn-attempt)
(def ^:private spawn-session-attribute :agent-executor/spawn-session-id)

(defn- clear-spawn-claim! [gate-id]
  (stamp! gate-id {spawn-session-attribute nil}))

(defn- claimed-runs [session-id]
  (weaver/list
   (rt)
   [:and
    [:= [:attr "harness/run"] "true"]
    [:= [:attr "harness/session-id"] session-id]]
   {}))

(defn- claimed-run [gate-id session-id]
  (let [runs (claimed-runs session-id)]
    (case (count runs)
      0 nil
      1 (let [run (first runs)]
          (when-let [served-gate (served-gate-id (:id run))]
            (fail! "Agent spawn claim identifies a run serving another gate"
                   {:gate gate-id
                    :session-id session-id
                    :run (:id run)
                    :served-gate served-gate}))
          run)
      (fail! "Agent spawn claim identifies multiple harness runs"
             {:gate gate-id
              :session-id session-id
              :runs (mapv :id runs)}))))

(defn- link-run! [workflow-run-id gate-id run]
  (weaver/update!
   (rt)
   (:id run)
   {:attributes {"workflow/run-id" workflow-run-id}
    :edges [{:type "serves" :to gate-id}]})
  (clear-spawn-claim! gate-id)
  run)

(defn- validated-request [workflow-run-id gate session-id]
  (let [alias (attr gate :harness/alias)
        requested-mode (attr gate :harness/mode)
        requested-prompt (attr gate :harness/prompt)
        prompt (gate-prompt gate)]
    (require-valid! :harness/alias alias
                    "Agent gate requires harness/alias")
    (when (some? requested-mode)
      (fail! "Agent workflow gates support headless runs only"
             {:gate (:id gate) :harness/mode requested-mode}))
    (when (some? requested-prompt)
      (require-valid! :harness/prompt requested-prompt
                      "Agent gate harness/prompt must be a non-blank string"))
    (require-valid! non-blank-string? prompt
                    "Agent gate requires harness/prompt or a derivable instruction")
    (when (some? (attr gate :harness/cwd))
      (require-valid! :harness/cwd (attr gate :harness/cwd)
                      "Agent gate harness/cwd must be a non-blank string"))
    (let [resolved (harnesses/resolve-harness (rt) alias)]
      (when-not (contains? (get-in resolved [:definition :modes]) :headless)
        (fail! "Agent gate harness does not support headless runs"
               {:gate (:id gate) :harness alias})))
    (cond-> {:harness alias
             :prompt (agent-prompt gate workflow-run-id prompt)
             :attributes (gate-overrides gate)
             :session-id session-id
             :title (str "Agent: " (:title gate))}
      (some? (attr gate :harness/cwd))
      (assoc :cwd (attr gate :harness/cwd)))))

(defn- fail-spawn! [gate-id session-id throwable]
  (stamp! gate-id
          {spawn-session-attribute nil
           "gate/error"
           (str "agent spawn failed after " max-spawn-attempts
                " attempts for session " session-id ": "
                (error-detail throwable))}))

(defn- attempt-spawn [gate-id request session-id]
  (try
    {:run (or (claimed-run gate-id session-id)
              (harnesses/create! (rt) request))}
    (catch Throwable throwable
      ;; `create!` may commit the run before a later identity operation fails.
      ;; Adopt that run before consuming another attempt.
      {:run (claimed-run gate-id session-id)
       :error throwable})))

(defn- spawn-with-retries! [workflow-run-id gate request session-id first-attempt]
  (let [gate-id (:id gate)]
    (loop [attempt first-attempt]
      (if (> attempt max-spawn-attempts)
        (fail-spawn! gate-id session-id
                     (ex-info "incomplete spawn claim has no harness run"
                              {:gate gate-id :session-id session-id}))
        (do
          (stamp! gate-id {spawn-attempt-attribute attempt
                           spawn-session-attribute session-id})
          (let [{:keys [run error]} (attempt-spawn gate-id request session-id)]
            (cond
              run (link-run! workflow-run-id gate-id run)
              (< attempt max-spawn-attempts) (recur (inc attempt))
              :else (fail-spawn! gate-id session-id error))))))))

(defn- reconcile-spawn! [workflow-run-id gate]
  (let [gate-id (:id gate)
        existing-session-id (attr gate spawn-session-attribute)
        session-id (or existing-session-id (str (UUID/randomUUID)))
        previous-attempt (if existing-session-id
                           (or (attr gate spawn-attempt-attribute) 0)
                           0)]
    (require-valid! nat-int? previous-attempt
                    "Agent gate spawn attempt must be a natural integer")
    (if-let [run (claimed-run gate-id session-id)]
      (link-run! workflow-run-id gate-id run)
      (let [request (validated-request workflow-run-id gate session-id)]
        (spawn-with-retries! workflow-run-id gate request session-id
                             (inc previous-attempt))))))

(defn- spawn-for-gate! [workflow-run-id gate-view]
  (let [gate-id (:id gate-view)]
    (try
      (let [gate (weaver/show (rt) gate-id)]
        (when (and (= "active" (:state gate))
                   (ready-gate? workflow-run-id gate-id)
                   (not (some? (attr gate :gate/error))))
          (if (serving-run gate-id)
            (when (some? (attr gate spawn-session-attribute))
              (clear-spawn-claim! gate-id))
            (reconcile-spawn! workflow-run-id gate))))
      (catch Throwable throwable
        (stamp! gate-id {"gate/error" (error-detail throwable)})))))

(defn- spawn-ready-gates! []
  (doseq [root (workflow/active-runs)
          :let [workflow-run-id (attr root :workflow/run-id)]
          gate (workflow/ready workflow-run-id)
          :when (= "agent" (:gate gate))]
    (spawn-for-gate! workflow-run-id gate)))

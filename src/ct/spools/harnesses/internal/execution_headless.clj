(ns ct.spools.harnesses.internal.execution-headless
  "Headless launch planning, process custody, and terminal transitions."
  (:require [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.assignment :as assignment]
            [ct.spools.harnesses.native-session :as native-session]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.launcher :as launcher]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.native-environment :as native-env]
            [ct.spools.harnesses.internal.process-custody :as custody]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- callback [symbol]
  (or (requiring-resolve symbol)
      (fail! "Harness callback cannot be resolved" {:callback symbol})))

(defn- run? [run]
  (= "true" (attr-get run :harness/run)))

(defn ready-headless
  "Return published, assignment-ready headless runs eligible to launch."
  [rt]
  (filterv #(and (run? %)
                 (life/published? %)
                 (= "ready" (life/status %))
                 (= "headless" (attr-get % :harness/mode))
                 (assignment/launch-ready? rt %))
           (mapv #(guidance/validation-run rt %)
                 (weaver/ready rt))))

(defn claim!
  "Claim one run ID in an opened generation; return whether this call won."
  [opened id]
  (let [[before _] (swap-vals! (:in-flight opened) conj id)]
    (not (contains? before id))))

(defn release-opened!
  "Release one run ID from its opened-generation launch claim."
  [opened id]
  (swap! (:in-flight opened) disj id))

(defn full-run
  "Return one run by ID, or fail when it is absent."
  [rt id]
  (guidance/validation-run
   rt (or (weaver/show rt id) (fail! "Harness run not found" {:id id}))))

(defn resolved-definition
  "Return the concrete provider definition frozen on one run."
  [rt run]
  (harness/concrete-harness rt (attr-get run :harness/harness)))

(defn prepare-launch
  "Invoke and validate a provider's launch preparation callback."
  [rt definition run]
  (let [launch-spec ((callback (:prepare definition)) rt definition run)
        alias-env (into {}
                        (map (fn [[name value]]
                               [(clojure.core/name name) value]))
                        (or (attr-get run :harness/env) {}))]
    (require-valid!
     :ct.spools.harnesses/launch-spec
     (update launch-spec :env #(merge alias-env (or % {})))
     "Harness prepare must return a valid launch specification")))

(defn process-spec
  "Build Mill custody input with run correlation and maintenance identity."
  [rt run {:keys [argv env stdin]}]
  {:argv (case (attr-get run :harness/harness)
           "codex" (into ["/usr/bin/env" "-u" "MILLSTRAND_AGENT_ID"
                          "-u" "MILLSTRAND_MANAGED_BOOTSTRAP"
                          "-u" "MILLSTRAND_MANAGED_GUIDANCE"] argv)
           "pi" (native-env/scrub-command argv)
           argv)
   :cwd (attr-get run :harness/cwd)
   :env (case (attr-get run :harness/harness)
          "codex"
          (-> (or env {})
              (dissoc "MILLSTRAND_AGENT_ID"
                      "MILLSTRAND_MANAGED_BOOTSTRAP"
                      "MILLSTRAND_MANAGED_GUIDANCE")
              (assoc "MILLSTRAND_RUN_ID" (:id run)
                     "MILLSTRAND_WORKSPACE" (launcher/workspace rt)
                     "MILLSTRAND_RUN_REFERENCE" (native-session/reference run)))
          "pi"
          (assoc (apply dissoc (or env {}) native-env/ownership-keys)
                 "MILLSTRAND_RUN_ID" (:id run))
          (cond-> (assoc (or env {})
                         "MILLSTRAND_RUN_ID" (:id run)
                         "MILLSTRAND_WORKSPACE" (launcher/workspace rt))
            (attr-get run :identity/id)
            (assoc "MILLSTRAND_AGENT_ID" (attr-get run :identity/id))))
   :stdin stdin})

(defn finish-process!
  "Persist one terminal custody fact and acknowledge its opaque handle."
  [callbacks rt run definition record]
  (weaver/update! rt (:id run)
                  {:attributes (custody/durable-attributes
                                "harness" (:id run)
                                (attr-get run :harness/attempt) record)})
  (let [raw (custody/terminal-observed record)
        evidence (cond-> (life/settlement-evidence raw)
                   (:cancellation raw) (assoc :cancelled? true))
        observed (select-keys raw [:exit-code :stdout :stderr])
        observed (if (some? (:exit-code observed))
                   observed
                   (assoc observed :exit-code 1
                          :stderr (or (:stderr observed)
                                      (custody/terminal-error raw)
                                      "Process custody terminal failure")))
        outcome (assoc ((callback (:finish definition))
                        rt definition run observed)
                       :invocation (life/invocation run))]
    (if (life/terminal? ((:full-run callbacks) rt (:id run)))
      (harness/settle-outcome! rt (:id run) outcome evidence)
      (harness/finish! rt (:id run) (assoc outcome :evidence evidence)))
    (custody/acknowledge! rt record)))

(defn enforce-stop!
  "Cancel an owned nonterminal record when durable stop intent exists."
  [rt run record]
  (when (and (life/stop-requested? run) (not= :terminal (:phase record)))
    (custody/cancel! rt record)))

(defn launch-headless!
  "Launch one already-claimed pending headless run."
  [{:keys [full-run inspect-owned! release-opened! schedule! state
           state-holder] :as callbacks}
   launch-state rt id]
  (try
    (let [candidate (full-run rt id)]
      (when-not (and (= "ready" (life/status candidate))
                     (assignment/launch-ready? rt candidate))
        (throw (ex-info "Harness run is no longer ready to launch"
                        {:run-id id :deferred true}))))
    (let [{:keys [attempt invocation]} (harness/begin-attempt! rt id)]
      (try
        (let [run (full-run rt id)
              definition (resolved-definition rt run)
              launch-spec (prepare-launch rt definition run)
              _ (weaver/update! rt id
                                {:attributes
                                 (custody/durable-attributes
                                  "harness" id attempt
                                  {:handle "pending" :phase :starting})})
              record (custody/launch! rt id attempt
                                      (process-spec rt run launch-spec))]
          (weaver/update! rt id
                          {:attributes
                           (custody/durable-attributes "harness" id attempt record)})
          (if (= :terminal (:phase record))
            (finish-process! callbacks rt (full-run rt id) definition record)
            (do
              (enforce-stop! rt (full-run rt id) record)
              (inspect-owned! rt (or launch-state (state rt)))))
          invocation)
        (catch Exception error
          (if (life/terminal? (full-run rt id))
            (throw error)
            (let [current (full-run rt id)
                  error-data (ex-data error)
                  message (str (ex-message error)
                               (when error-data
                                 (str " " (pr-str error-data))))
                  evidence
                  (if (= "process/malformed-launch" (:code error-data))
                    (assoc (life/settlement-evidence
                            {:launch-failure error-data})
                           :failure-class "launch")
                    {:settled false
                     :settlement "no-terminal-evidence"
                     :failure-class
                     (if (attr-get current :harness/process-handle)
                       "execution"
                       "launch")})
                  transition-error
                  (try
                    (harness/finish!
                     rt id
                     {:status :failed
                      :invocation invocation
                      :evidence evidence
                      :error message})
                    nil
                    (catch Throwable finish-error finish-error))]
              (when transition-error
                (throw (ex-info "Unable to persist harness launch failure"
                                {:run-id id
                                 :launch-error {:message (ex-message error)
                                                :data (ex-data error)}
                                 :failure-transition-error
                                 {:message (ex-message transition-error)
                                  :data (ex-data transition-error)}}
                                transition-error))))))))
    (catch Exception error
      (when-not (:deferred (ex-data error))
        (throw error)))
    (finally
      (let [opened (or launch-state (state rt))]
        (release-opened! opened id)
        (when (identical? opened @(:active (state-holder rt)))
          (inspect-owned! rt opened)
          (schedule! rt))))))

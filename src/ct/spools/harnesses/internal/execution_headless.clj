(ns ct.spools.harnesses.internal.execution-headless
  "Headless launch planning, process custody, and terminal transitions."
  (:require [clojure.data.json :as json]
            [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.assignment :as assignment]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.launcher :as launcher]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-startup :as managed]
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
           (weaver/ready rt)))

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
  (or (weaver/show rt id) (fail! "Harness run not found" {:id id})))

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

(defn- current-launch-selectors [run]
  (cond-> {"extra-argv" (or (attr-get run :harness/extra-argv) [])
           "resumes" (boolean (attr-get run :harness/resumes))}
    (attr-get run :harness/model)
    (assoc "model" (attr-get run :harness/model))
    (attr-get run :harness/effort)
    (assoc "effort" (attr-get run :harness/effort))
    (attr-get run :harness/resumes)
    (assoc "native-session-id" (attr-get run :harness/session-id))))

(defn- launch-plan-failure! [run reason data]
  (fail! "Native guidance launch no longer matches its validated preflight"
         (merge {:code "guidance/launch-plan-mismatch"
                 :run-id (:id run)
                 :reason reason}
                data)))

(defn apply-native-launch-plan
  "Apply only an exact transient native launch plan to a launch spec."
  [run launch-spec plan]
  (if-not (guidance/native? run)
    launch-spec
    (let [{:keys [argv env]} launch-spec
          harness (attr-get run :harness/harness)
          canonical-cwd (.getCanonicalPath
                         (java.io.File. ^String
                          (attr-get run :harness/cwd)))]
      (when-not (and (map? plan)
                     (= harness (:harness plan))
                     (string? (:executable plan))
                     (= (:executable plan)
                        (.getCanonicalPath
                         (java.io.File. ^String (:executable plan))))
                     (= canonical-cwd (:cwd plan))
                     (map? (:env plan))
                     (every? (fn [[key value]]
                               (and (string? key) (string? value)))
                             (:env plan))
                     (= (current-launch-selectors run) (:selectors plan)))
        (launch-plan-failure! run "transient plan fields changed" {}))
      (when-not (= harness (first argv))
        (launch-plan-failure! run "provider command changed"
                              {:provider-command (first argv)}))
      (when-not (= (:env plan) (merge (:env plan) (or env {})))
        (launch-plan-failure! run "provider environment changed" {}))
      (assoc launch-spec
             :argv (assoc argv 0 (:executable plan))
             :env (:env plan)
             :validated-cwd (:cwd plan)))))

(defn process-spec
  "Build Mill custody input with reserved run and workspace environment."
  [rt run {:keys [argv env stdin validated-cwd]}]
  (let [bootstrap (managed/bootstrap rt run)
        guidance-document
        (when (and bootstrap
                   (some? (attr-get run :harness/guidance-version)))
          (guidance/bootstrap run))]
    {:argv argv
     :cwd (or validated-cwd (attr-get run :harness/cwd))
     :env (cond-> (assoc (or env {})
                         "MILLSTRAND_RUN_ID" (:id run)
                         "MILLSTRAND_WORKSPACE" (launcher/workspace rt))
            (attr-get run :identity/id)
            (assoc "MILLSTRAND_AGENT_ID" (attr-get run :identity/id))
            bootstrap
            (assoc "MILLSTRAND_MANAGED_BOOTSTRAP" (json/write-str bootstrap))
            guidance-document
            (assoc "MILLSTRAND_MANAGED_GUIDANCE"
                   (json/write-str guidance-document)))
     :stdin stdin}))

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
    (let [{:keys [attempt invocation] :as started}
          (harness/begin-attempt! rt id)
          launch-plan (guidance/launch-plan started)]
      (try
        (let [run (full-run rt id)
              definition (resolved-definition rt run)
              launch-spec (apply-native-launch-plan
                           run (prepare-launch rt definition run) launch-plan)
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
                  (if (contains? #{"process/malformed-launch"
                                   "guidance/launch-plan-mismatch"}
                                 (:code error-data))
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

(ns ct.spools.harnesses.reconciliation-test
  "Process evidence and administrative reconciliation contract tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.reconciliation :as reconciliation]
            [millstrand.test.alpha :as test-alpha]))

(defn- run
  ([id status] (run id status nil))
  ([id status substatus]
   {:id id
    :attributes (cond-> {:harness/run "true"
                         :harness/status status}
                  substatus (assoc :harness/substatus substatus))}))

(deftest interactive-reconciliation-preserves-ambiguity-and-live-custody
  (let [running (assoc-in (run "legacy" "running" "requested")
                          [:attributes :harness/harness] "pi")
        classify #(reconciliation/classification running %)]
    (testing "legacy and unavailable observations stay unknown"
      (is (= "unknown"
             (:classification
              (classify {:completion-owner {:state "missing"}
                         :provider {:state "missing"}
                         :native {:state "not-observed"}}))))
      (is (= "unknown"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "gone"}
                         :native {:state "unavailable"}})))))
    (testing "live, idle, and newer native writers are protected"
      (is (= "live"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "live"}
                         :native {:state "not-observed"}}))))
      (is (= "live"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "gone"}
                         :native {:state "active"}}))))
      (is (= "protected"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "gone"}
                         :native {:state "not-observed"}
                         :active-session-writers ["newer"]}))))
      (doseq [provider-state ["gone" "missing" "unavailable"]]
        (is (= "protected"
               (:classification
                (classify {:completion-owner {:state "live"}
                           :provider {:state provider-state}
                           :native {:state "not-observed"}}))))))
    (testing "both absent or PID-reused identities prove orphaned custody"
      (doseq [state ["gone" "replaced"]]
        (is (= "orphaned"
               (:classification
                (classify {:completion-owner {:state state}
                           :provider {:state state}
                           :native {:state "not-observed"}})))))))
  (testing "abandonment retains target and session reservations"
    (is (true? (life/reserving? (run "old" "stopped" "abandoned")))))
  (testing "the abandonment patch never fabricates process-exit evidence"
    (let [patch (reconciliation/abandonment-patch
                 (run "old" "running")
                 {:at "2026-09-13T00:00:00Z"
                  :by "operator"
                  :reason "launcher is unrecoverable"
                  :source "attested"
                  :evidence {:provider {:state "missing"}}})
          attributes (:attributes patch)]
      (is (= "closed" (:state patch)))
      (is (= "abandoned" (:harness/substatus attributes)))
      (is (= "false" (:harness/settled attributes)))
      (is (= "interactive-abandoned" (:harness/settlement attributes)))
      (is (not (contains? attributes :harness/exit-code))))))

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    {:deps
     {'ct.spools/harnesses
      {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity
      {:local/root (.getCanonicalPath identity-root)}}}))

(defn- core-world-options [storage]
  {:storage storage
   :deps-edn (pr-str (world-deps))
   :init-clj
   "(require '[millstrand.api.current.alpha :as current]
             '[millstrand.api.runtime.alpha :as runtime])
    (def rt (current/runtime))
    (runtime/module! rt :identity
      {:ns 'millhouse.spools.identity
       :required? true})
    (runtime/module! rt :harnesses-core
      {:file \"modules/lifecycle_core.clj\"
       :after [:identity]
       :required? true})"
   :files
   {"modules/lifecycle_core.clj"
    "(ns modules.lifecycle-core
       (:require [ct.spools.harnesses :as harnesses]
                 [ct.spools.harnesses.agent-cli :as agent-cli]
                 [ct.spools.harnesses.assignment :as assignment]
                 [ct.spools.harnesses.queries :as queries]
                 [millstrand.api.lifecycle.alpha :as lifecycle]
                 [millstrand.api.millstrand.alpha :as millstrand]))
     (lifecycle/use-resource!
      harnesses/harness-core-runtime
      assignment/assignment-runtime)
     (millstrand/use-op! agent-cli/agent)
     (millstrand/use-query!
      queries/agent-run-terminal
      queries/agent-run-settled
      queries/agent-run-active
      queries/agent-runs-active
      queries/agent-runs-for-target
      queries/agent-work-complete
      queries/agent-work-complete-or-intervention
      queries/agent-work-root-complete
      queries/agent-work-root-complete-or-intervention)"}})

(defn- with-core-world [f]
  (test-alpha/run-with-weaver-world (core-world-options :sqlite-memory) f))

(deftest malformed-probes-cannot-bypass-live-or-terminal-protection
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.reconciliation :as reconcile]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      _ (harnesses/register-harness!
                         rt :cursor
                         {:modes #{:interactive}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      pid (.pid (java.lang.ProcessHandle/current))
                      owner (reconcile/completion-owner-attributes pid)
                      run (harnesses/create!
                           rt {:harness :cursor :mode :interactive})
                      started (harnesses/begin-attempt! rt (:id run) owner)
                      _ (reconcile/register-provider!
                         rt (:id run) (:invocation started) pid)
                      _ (weaver/update!
                         rt (:id run)
                         {:attributes {:harness/provider-started-at 123}})
                      before-live (weaver/show rt (:id run))
                      inspected
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (weaver/op! rt 'agent ["reconcile" (:id run)]))
                      live-refusal
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (try
                          (weaver/op!
                           rt 'agent
                           ["reconcile" (:id run) "--abandon"
                            "--reason" "malformed provider evidence"
                            "--by-identity"
                            (spool/attr-get run :identity/id)])
                          nil
                          (catch clojure.lang.ExceptionInfo error
                            (ex-message error))))
                      live-no-write (= before-live (weaver/show rt (:id run)))
                      completed
                      (harnesses/finish!
                       rt (:id run)
                       {:status :done
                        :exit-code 0
                        :session-usable true
                        :invocation (:invocation started)
                        :evidence {:settled true
                                   :settlement "process-exit"}})
                      before-terminal (weaver/show rt (:id run))
                      terminal-refusal
                      (try
                        (weaver/op!
                         rt 'agent
                         ["reconcile" (:id run) "--abandon"
                          "--reason" "must not rewrite completion"
                          "--by-identity"
                          (spool/attr-get run :identity/id)])
                        nil
                        (catch clojure.lang.ExceptionInfo error
                          (ex-message error)))
                      terminal-no-write
                      (= before-terminal (weaver/show rt (:id run)))]
                  {:classification
                   (get-in inspected [:runs 0 :classification])
                   :owner-state
                   (get-in inspected
                           [:runs 0 :evidence :completion-owner :state])
                   :provider-state
                   (get-in inspected [:runs 0 :evidence :provider :state])
                   :live-refusal live-refusal
                   :live-no-write live-no-write
                   :completed-status
                   [(spool/attr-get completed :harness/status)
                    (spool/attr-get completed :harness/substatus)
                    (spool/attr-get completed :harness/settled)
                    (spool/attr-get completed :harness/exit-code)]
                   :terminal-refusal terminal-refusal
                   :terminal-no-write terminal-no-write})))]
        (is (= "protected" (:classification result)))
        (is (= "live" (:owner-state result)))
        (is (= "unavailable" (:provider-state result)))
        (is (re-find #"refuses known live or ineligible"
                     (:live-refusal result)))
        (is (true? (:live-no-write result)))
        (is (= ["stopped" "completed" "true" 0]
               (:completed-status result)))
        (is (re-find #"refuses known live or ineligible"
                     (:terminal-refusal result)))
        (is (true? (:terminal-no-write result)))))))

(deftest explicit-legacy-abandonment-is-auditable-and-idempotent
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.reconciliation :as reconcile]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      _ (harnesses/register-harness!
                         rt :codex
                         {:modes #{:interactive}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      pid (.pid (java.lang.ProcessHandle/current))
                      start-attributes (reconcile/completion-owner-attributes pid)
                      live (harnesses/create!
                            rt {:harness :codex :mode :interactive})
                      live-start (harnesses/begin-attempt!
                                  rt (:id live) start-attributes)
                      _ (reconcile/register-provider!
                         rt (:id live) (:invocation live-start) pid)
                      live-result
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (weaver/op! rt 'agent ["reconcile" (:id live)]))
                      live-abandon-refused?
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (try
                          (weaver/op!
                           rt 'agent
                           ["reconcile" (:id live) "--abandon"
                            "--reason" "must not override live evidence"
                            "--by-identity"
                            (spool/attr-get live :identity/id)])
                          false
                          (catch clojure.lang.ExceptionInfo _ true)))
                      reused (harnesses/create!
                              rt {:harness :codex :mode :interactive})
                      reused-start (harnesses/begin-attempt!
                                    rt (:id reused) start-attributes)
                      _ (reconcile/register-provider!
                         rt (:id reused) (:invocation reused-start) pid)
                      _ (weaver/update!
                         rt (:id reused)
                         {:attributes
                          {:harness/completion-owner-started-at
                           "1970-01-01T00:00:00Z"
                           :harness/provider-started-at
                           "1970-01-01T00:00:00Z"}})
                      reused-result
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (weaver/op! rt 'agent ["reconcile" (:id reused)]))
                      target (weaver/add! rt {:title "Reconciliation target"})
                      legacy (harnesses/create!
                              rt {:harness :codex :mode :interactive
                                  :target (:id target)})
                      _ (harnesses/begin-attempt! rt (:id legacy))
                      actor "reconciliation-operator"
                      before-dry-run (weaver/show rt (:id legacy))
                      dry-run (weaver/op! rt 'agent
                                          ["reconcile" (:id legacy) "--dry-run"])
                      dry-run-unchanged?
                      (= before-dry-run (weaver/show rt (:id legacy)))
                      abandoned
                      (weaver/op! rt 'agent
                                  ["reconcile" (:id legacy) "--abandon"
                                   "--reason" "launcher ownership was lost"
                                   "--by-identity" actor])
                      repeated
                      (weaver/op! rt 'agent
                                  ["reconcile" (:id legacy) "--abandon"
                                   "--reason" "launcher ownership was lost"
                                   "--by-identity" actor])
                      stored (weaver/show rt (:id legacy))
                      intervention?
                      (boolean
                       (seq (weaver/list-query
                             rt 'agent-work-complete-or-intervention
                             {:target (:id target)})))
                      target-blocked?
                      (try
                        (harnesses/create!
                         rt {:harness :codex :mode :interactive
                             :target (:id target)})
                        false
                        (catch clojure.lang.ExceptionInfo _ true))
                      same-session-blocked?
                      (try
                        (harnesses/create!
                         rt {:harness :codex :mode :interactive
                             :session-id
                             (spool/attr-get stored :harness/session-id)})
                        false
                        (catch clojure.lang.ExceptionInfo _ true))]
                  {:live live-result
                   :live-abandon-refused? live-abandon-refused?
                   :reused reused-result
                   :dry-run dry-run
                   :dry-run-unchanged? dry-run-unchanged?
                   :abandoned abandoned
                   :repeated repeated
                   :stored
                   {:status (spool/attr-get stored :harness/status)
                    :substatus (spool/attr-get stored :harness/substatus)
                    :settled (spool/attr-get stored :harness/settled)
                    :exit-code (spool/attr-get stored :harness/exit-code)
                    :reason (spool/attr-get stored :harness/abandon-reason)
                    :source (spool/attr-get stored
                                            :harness/reconciliation-source)}
                   :intervention? intervention?
                   :target-blocked? target-blocked?
                   :same-session-blocked? same-session-blocked?
                   :action-actors
                   (mapv :by-identity
                         (millstrand.api.notes.alpha/notes
                          rt (:id legacy) {}))})))]
        (is (= [] (get-in result [:live :changed])))
        (is (= "live" (get-in result [:live :runs 0 :classification])))
        (is (true? (:live-abandon-refused? result)))
        (is (= [(get-in result [:reused :runs 0 :id])]
               (get-in result [:reused :changed])))
        (is (= "replaced"
               (get-in result
                       [:reused :runs 0 :evidence :provider :state])))
        (is (= "replaced"
               (get-in result
                       [:reused :runs 0 :evidence
                        :completion-owner :state])))
        (is (= [] (get-in result [:dry-run :changed])))
        (is (true? (:dry-run-unchanged? result)))
        (is (= "unknown"
               (get-in result [:dry-run :runs 0 :classification])))
        (is (= [(:id (first (get-in result [:abandoned :runs])))]
               (get-in result [:abandoned :changed])))
        (is (= [] (get-in result [:repeated :changed])))
        (is (= [(get-in result [:abandoned :runs 0 :abandoned-by])]
               (:action-actors result)))
        (is (= {:status "stopped"
                :substatus "abandoned"
                :settled "false"
                :exit-code nil
                :reason "launcher ownership was lost"
                :source "attested"}
               (:stored result)))
        (is (true? (:intervention? result)))
        (is (true? (:target-blocked? result)))
        (is (true? (:same-session-blocked? result)))))))

(ns ct.spools.harnesses.lifecycle-test
  "Lifecycle contract tests: status/substatus, stop, resume, and wait queries."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.cli :as cli]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [millstrand.test.alpha :as test-alpha]))

(defn- run
  ([id status] (run id status nil))
  ([id status substatus]
   {:id id
    :attributes (cond-> {:harness/run "true"
                         :harness/status status}
                  substatus (assoc :harness/substatus substatus))}))

(deftest running-has-no-pending-substatus
  (is (nil? (life/substatus (run "r" "running"))))
  (is (nil? (life/substatus {:attributes {:harness/run "true"
                                          :harness/phase "running"}})))
  (is (= "pending" (life/substatus (run "r" "ready" "pending")))))

(deftest reserving-includes-unsettled-terminal
  (is (true? (life/reserving? (run "r" "ready" "pending"))))
  (is (true? (life/reserving? (run "r" "running"))))
  (is (true? (life/reserving?
              {:id "r"
               :attributes {:harness/status "failed"
                            :harness/substatus "execution"
                            :harness/settled "false"}})))
  (is (false? (life/reserving?
               {:id "r"
                :attributes {:harness/status "failed"
                             :harness/substatus "execution"
                             :harness/settled "true"}})))
  (is (false? (life/reserving?
               {:id "r"
                :attributes {:harness/status "stopped"
                             :harness/substatus "completed"
                             :harness/settled "true"}}))))

(deftest terminal-patch-stop-races
  (let [running {:attributes {:harness/status "running"
                              :harness/stop-requested-at "t"}}
        ready {:attributes {:harness/status "ready"}}]
    (testing "normal completion racing stop stays completed"
      (is (= "completed"
             (:harness/substatus
              (life/terminal-patch running :done
                                   {:settled true :settlement "process-exit"}
                                   true))))
      (is (= "stopped"
             (:harness/status
              (life/terminal-patch running :done
                                   {:settled true :settlement "process-exit"}
                                   true)))))
    (testing "confirmed cancellation is requested"
      (is (= {:harness/status "stopped" :harness/substatus "requested"}
             (select-keys (life/terminal-patch
                           running :failed
                           {:settled true :settlement "graceful-cancellation"}
                           true)
                          [:harness/status :harness/substatus]))))
    (testing "prior failure remains failed"
      (is (= "failed"
             (:harness/status
              (life/terminal-patch ready :failed
                                   {:settled true :settlement "process-exit"}
                                   false))))
      (is (= "execution"
             (:harness/substatus
              (life/terminal-patch
               (assoc-in ready [:attributes :harness/invocation] "inv")
               :failed
               {:settled true :settlement "process-exit"}
               false)))))))

(deftest settlement-evidence-respects-mill-stop-class
  (is (= {:settled true :settlement "process-exit"}
         (life/settlement-evidence {:exit-code 0})))
  (is (= {:settled true :settlement "graceful-cancellation"}
         (life/settlement-evidence {:cancellation {:reason "stop"
                                                   :stop :graceful}})))
  (is (= "forced-cancellation"
         (:settlement (life/settlement-evidence
                       {:cancellation {:reason "stop" :stop :forced}}))))
  (is (false? (:settled (life/settlement-evidence
                         {:cancellation {:reason "stop" :stop :forced}}))))
  (is (false? (:settled (life/settlement-evidence
                         {:cancellation {:reason "cancelled by owner"}})))))

(deftest legacy-done-does-not-infer-session-usable
  (let [patch (life/migration-patch
               {:id "old"
                :attributes {:harness/run "true"
                             :harness/phase "done"
                             :harness/session-id "provisional-uuid"}})]
    (is (= "stopped" (:harness/status patch)))
    (is (= "completed" (:harness/substatus patch)))
    (is (= "false" (:harness/session-usable patch)))))

(deftest agent-await-is-removed
  (is (not (contains? (:subcommands cli/agent-arg-spec) "await"))))

(deftest reserved-agent-environment-cannot-be-overridden
  (let [runtime {:metadata {:config-dir "/runtime/workspace"}}
        run {:id "run-1"
             :attributes {:harness/cwd "/work"
                          :identity/id "agent-1"}}
        launch-spec {:argv ["agent"]
                     :env {"MILLSTRAND_RUN_ID" "attacker-run"
                           "MILLSTRAND_AGENT_ID" "attacker-agent"
                           "MILLSTRAND_WORKSPACE" "/wrong/workspace"
                           "OTHER" "kept"}
                     :stdin nil}
        launch (#'execution/process-spec runtime run launch-spec)]
    (is (= "run-1" (get-in launch [:env "MILLSTRAND_RUN_ID"])))
    (is (= "agent-1" (get-in launch [:env "MILLSTRAND_AGENT_ID"])))
    (is (= "/runtime/workspace"
           (get-in launch [:env "MILLSTRAND_WORKSPACE"])))
    (is (= "kept" (get-in launch [:env "OTHER"])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"no configured workspace"
         (#'execution/process-spec {:metadata {}} run launch-spec)))))

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

(defn with-core-world
  "Run a body in an isolated core lifecycle Weaver world."
  [f]
  (test-alpha/run-with-weaver-world (core-world-options :sqlite-memory) f))

(deftest work-scope-queries-use-positive-evidence
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.assignment :as assignment]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.graph.alpha :as graph]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      attr spool/attr-get
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:headless}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      hierarchy
                      (fn [prefix]
                        (let [task (weaver/add!
                                    rt {:title (str prefix " task")
                                        :attributes {:kanban/card "true"
                                                     :kanban/type "task"}})
                              feature (weaver/add!
                                       rt {:title (str prefix " feature")
                                           :attributes {:kanban/card "true"
                                                        :kanban/type "feature"}
                                           :edges [{:type "parent-of"
                                                    :to (:id task)}]})
                              epic (weaver/add!
                                    rt {:title (str prefix " epic")
                                        :attributes {:kanban/card "true"
                                                     :kanban/type "epic"}
                                        :edges [{:type "parent-of"
                                                 :to (:id feature)}]})]
                          {:epic epic :feature feature :task task}))
                      assign (fn [task options]
                               (assignment/assign!
                                rt (merge {:harness :fake
                                           :target (:id task)
                                           :cwd "/tmp/assignment-work"}
                                          options)))
                      queried? (fn [query target]
                                 (seq (weaver/list-query
                                       rt query {:target (:id target)})))
                      failed-scope (hierarchy "Failed")
                      failed-run (assign (:task failed-scope) {})
                      _ (harnesses/finish! rt (:id failed-run)
                                           {:status :failed :error "boom"})
                      failed-roots (mapv :to_strand_id
                                         (graph/outgoing-edges
                                          rt [(:id failed-run)] "serves-root"))
                      abandoned-scope (hierarchy "Abandoned")
                      abandoned-run (assign (:task abandoned-scope) {})
                      _ (harnesses/stop! rt (:id abandoned-run) {})
                      _ (weaver/update!
                         rt (get-in abandoned-scope [:task :id])
                         {:state "closed"
                          :attributes {:kanban/outcome "abandoned"}})
                      superseded-scope (hierarchy "Superseded")
                      predecessor (assign (:task superseded-scope)
                                          {:request-id "predecessor"})
                      _ (harnesses/finish!
                         rt (:id predecessor)
                         {:status :failed
                          :error "retryable"
                          :evidence {:settled true
                                     :settlement "process-exit"}})
                      continuation (assign (:task superseded-scope)
                                           {:request-id "continuation"
                                            :after (:id predecessor)})
                      ambiguous-task (weaver/add!
                                      rt {:title "Ambiguous task"
                                          :attributes {:kanban/card "true"}})
                      _ (doseq [title ["Parent A" "Parent B"]]
                          (weaver/add!
                           rt {:title title
                               :attributes {:kanban/card "true"}
                               :edges [{:type "parent-of"
                                        :to (:id ambiguous-task)}]}))
                      ambiguous (try
                                  (assign ambiguous-task {})
                                  :accepted
                                  (catch Exception error (ex-message error)))]
                  {:failed
                   {:feature (boolean (queried?
                                       'agent-work-root-complete-or-intervention
                                       (:feature failed-scope)))
                    :epic (boolean (queried?
                                    'agent-work-root-complete-or-intervention
                                    (:epic failed-scope)))
                    :roots (set failed-roots)
                    :expected-roots #{(get-in failed-scope [:feature :id])
                                      (get-in failed-scope [:epic :id])}}
                   :abandoned
                   {:feature (boolean (queried?
                                       'agent-work-root-complete-or-intervention
                                       (:feature abandoned-scope)))
                    :epic (boolean (queried?
                                    'agent-work-root-complete-or-intervention
                                    (:epic abandoned-scope)))
                    :complete (boolean (queried?
                                        'agent-work-root-complete
                                        (:epic abandoned-scope)))
                    :run-status (attr (weaver/show rt (:id abandoned-run))
                                      :harness/status)}
                   :malformed {:ambiguous ambiguous}
                   :superseded
                   {:epic (boolean (queried?
                                    'agent-work-root-complete-or-intervention
                                    (:epic superseded-scope)))
                    :continued (attr (weaver/show rt (:id predecessor))
                                     :harness/continued)
                    :continuation (:id continuation)}})))]
        (testing "a current task-run failure wakes every frozen ancestor"
          (is (= (:expected-roots (:failed result))
                 (:roots (:failed result))))
          (is (true? (get-in result [:failed :feature])))
          (is (true? (get-in result [:failed :epic]))))
        (testing "an abandoned task wakes ancestors but is not completion"
          (is (= "stopped" (get-in result [:abandoned :run-status])))
          (is (true? (get-in result [:abandoned :feature])))
          (is (true? (get-in result [:abandoned :epic])))
          (is (false? (get-in result [:abandoned :complete]))))
        (testing "an accepted continuation excludes its failed predecessor"
          (is (= "true" (get-in result [:superseded :continued])))
          (is (string? (get-in result [:superseded :continuation])))
          (is (false? (get-in result [:superseded :epic]))))
        (testing "ambiguous parent scopes fail loudly"
          (is (re-find #"ambiguous work-card parents"
                       (get-in result [:malformed :ambiguous]))))))))

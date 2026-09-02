(ns ct.spools.harnesses.executors.agent-test
  "Integration tests for the Harnesses-backed Workflow agent executor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.executors.agent]
            [millstrand.test.alpha :as test-alpha]))

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")
        workflow-root (test-alpha/spool-checkout-root
                       "millhouse/spools/workflow.clj")]
    {:deps
     {'ct.spools/harnesses
      {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity
      {:local/root (.getCanonicalPath identity-root)}
      'millhouse.spools/workflow
      {:local/root (.getCanonicalPath workflow-root)}}}))

(defn- with-agent-world [f]
  (test-alpha/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn (pr-str (world-deps))
          :init-clj
          "(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity
              :required? true})
           (runtime/module! rt :workflow
             {:ns 'millhouse.spools.workflow
              :required? true})
           (runtime/module! rt :agent-test
             {:file \"modules/agent_test.clj\"
              :after [:identity :workflow]
              :required? true})"
          :files
          {"modules/agent_test.clj"
           "(ns modules.agent-test
              (:require [ct.spools.harnesses :as harnesses]
                        [ct.spools.harnesses.executors.agent :as agent]
                        [millhouse.spools.workflow :as workflow]
                        [millstrand.api.lifecycle.alpha :as lifecycle]
                        [millstrand.api.millstrand.alpha :as millstrand]))
            (workflow/use-executor! agent/agent-stalled?)
            (millstrand/use-query! agent/stalled-agent-gates)
            (lifecycle/use-resource! harnesses/harness-core-runtime)"}}]
    (f ctx)))

(defn- with-active-agent-world [f]
  (test-alpha/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn (pr-str (world-deps))
          :init-clj
          "(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity
              :required? true})
           (runtime/module! rt :workflow
             {:ns 'millhouse.spools.workflow
              :required? true})
           (runtime/module! rt :harnesses
             {:ns 'ct.spools.harnesses.spool
              :after [:identity]
              :required? true})
           (runtime/module! rt :agent-executor
             {:ns 'ct.spools.harnesses.executors.agent.spool
              :after [:workflow :harnesses]
              :required? true})"}]
    (f ctx)))

(deftest adapter-selector-publishes-workflow-integration
  (with-active-agent-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[millhouse.spools.workflow :as workflow]
                         '[millstrand.api.events.alpha :as events]
                         '[millstrand.api.runtime.alpha :as runtime]
                         '[millstrand.api.weaver.alpha :as weaver])
                {:executor (contains? (workflow/executors) :agent)
                 :query (vector? (weaver/list-query
                                  rt 'stalled-agent-gates {}))
                 :handler (some #(when (= :agent/engine (:key %)) %)
                                (events/handlers rt))
                 :lifecycle (get-in (runtime/status rt)
                                    [:last-refresh :modules :agent-executor
                                     :lifecycle/outcomes :agent-engine
                                     :status])}))]
        (is (true? (:executor result)))
        (is (true? (:query result)))
        (is (= #{:strand/added :strand/updated :batch/applied :strand/burned
                 :strand/superseded}
               (get-in result [:handler :types])))
        (is (= :applied (:lifecycle result)))))))

(deftest agent-gates-spawn-deliver-stall-and-retry
  (with-agent-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.executors.agent :as agent]
                         '[millhouse.spools.workflow :as workflow]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                ;; Exercise the adapter directly without activating asynchronous
                ;; harness execution or launching a provider subprocess.
                (harnesses/register-harness!
                 rt :fake
                 {:modes #{:headless}
                  :prepare 'ct.spools.harnesses/create!
                  :finish 'ct.spools.harnesses/finish!})
                (let [attr #(spool/attr-get %1 %2)
                      gate-by-title
                      (fn [title]
                        (first (weaver/list
                                rt
                                [:and [:= :title title]
                                 [:= [:attr "workflow/gate"] "agent"]]
                                {})))
                      runs-for-gate
                      (fn [gate-id]
                        (weaver/list
                         rt
                         [:and [:= [:attr "harness/run"] "true"]
                          [:edge/out "serves" [:= :id gate-id]]]
                         {}))
                      happy-definition
                      (workflow/workflow
                       "Happy agent"
                       (workflow/gate
                        :delegate "Happy gate" :agent
                        :attributes
                        {"harness/alias" "fake"
                         "harness/prompt" "Return the implementation report."
                         "harness/cwd" "/tmp"
                         "harness/model" "test-model"
                         "harness.pi/thinking" "high"})
                       (workflow/step :after "After happy" :self
                                      :depends-on [:delegate]))
                      _ (workflow/start! "happy-agent" happy-definition {})
                      _ (agent/scan!)
                      happy-gate (gate-by-title "Happy gate")
                      happy-runs (runs-for-gate (:id happy-gate))
                      happy-run (first happy-runs)
                      _ (agent/scan!)
                      duplicate-count (count (runs-for-gate (:id happy-gate)))
                      _ (harnesses/finish!
                         rt (:id happy-run)
                         {:status :done
                          :exit-code 0
                          :result "implemented"})
                      _ (agent/scan!)
                      delivered-run (weaver/show rt (:id happy-run))
                      delivered-gate (weaver/show rt (:id happy-gate))
                      failed-definition
                      (workflow/workflow
                       "Failed agent"
                       (workflow/gate
                        :delegate "Failed gate" :agent
                        :attributes {"harness/alias" "fake"
                                     "harness/prompt" "Fail first."})
                       (workflow/step :after "After retry" :self
                                      :depends-on [:delegate]))
                      _ (workflow/start! "failed-agent" failed-definition {})
                      _ (agent/scan!)
                      failed-gate (gate-by-title "Failed gate")
                      failed-run (first (runs-for-gate (:id failed-gate)))
                      _ (harnesses/finish!
                         rt (:id failed-run)
                         {:status :failed
                          :exit-code 7
                          :error "provider failed"})
                      stall (agent/agent-stalled?
                             (first (workflow/ready "failed-agent")))
                      stalled-query-ids
                      (set (map :id
                                (weaver/list-query
                                 rt 'stalled-agent-gates {})))
                      retried (harnesses/retry! rt (:id failed-run) {})
                      phase-after-retry (attr retried :harness/phase)
                      _ (agent/scan!)
                      retry-run-ids (mapv :id
                                          (runs-for-gate (:id failed-gate)))
                      phase-after-scan
                      (attr (weaver/show rt (:id failed-run)) :harness/phase)
                      _ (harnesses/finish!
                         rt (:id failed-run)
                         {:status :done
                          :exit-code 0
                          :result "recovered"})
                      _ (agent/scan!)
                      recovered-gate (weaver/show rt (:id failed-gate))
                      missing-definition
                      (workflow/workflow
                       "Missing alias"
                       (workflow/gate
                        :delegate "Missing alias gate" :agent
                        :attributes {"harness/prompt" "Cannot start."}))
                      _ (workflow/start! "missing-agent" missing-definition {})
                      _ (agent/scan!)
                      missing-gate (gate-by-title "Missing alias gate")]
                  {:happy
                   {:run-count (count happy-runs)
                    :run-id (:id happy-run)
                    :duplicate-count duplicate-count
                    :alias (attr happy-run :harness/alias)
                    :cwd (attr happy-run :harness/cwd)
                    :model (attr happy-run :harness/model)
                    :thinking (attr happy-run :harness.pi/thinking)
                    :prompt (attr happy-run :harness/prompt)
                    :workflow-run-id (attr happy-run :workflow/run-id)
                    :delivered (attr delivered-run :gate/delivered)
                    :gate-state (:state delivered-gate)
                    :outcome-by (attr delivered-gate :workflow/outcome-by)
                    :result (attr delivered-gate :harness/result)
                    :next-title (:title (first (workflow/ready "happy-agent")))}
                   :failed
                   {:stall stall
                    :query-contains? (contains? stalled-query-ids
                                                (:id failed-gate))
                    :retry-run-ids retry-run-ids
                    :run-id (:id failed-run)
                    :phase-after-retry phase-after-retry
                    :phase-after-scan phase-after-scan
                    :gate-state (:state recovered-gate)
                    :result (attr recovered-gate :harness/result)}
                   :missing
                   {:error (attr missing-gate :gate/error)
                    :run-count (count (runs-for-gate (:id missing-gate)))}})))]
        (testing "a ready :agent gate creates one linked headless run"
          (is (= 1 (get-in result [:happy :run-count])))
          (is (= 1 (get-in result [:happy :duplicate-count])))
          (is (= "fake" (get-in result [:happy :alias])))
          (is (= "/tmp" (get-in result [:happy :cwd])))
          (is (= "test-model" (get-in result [:happy :model])))
          (is (= "high" (get-in result [:happy :thinking])))
          (is (= "happy-agent" (get-in result [:happy :workflow-run-id])))
          (is (re-find #"Do not close or mutate\s+strands in this workflow"
                       (get-in result [:happy :prompt])))
          (is (str/includes? (get-in result [:happy :prompt])
                             "Return the implementation report.")))
        (testing "a successful run closes the gate and unblocks its dependent"
          (is (= "true" (get-in result [:happy :delivered])))
          (is (= "closed" (get-in result [:happy :gate-state])))
          (is (= "implemented" (get-in result [:happy :result])))
          (is (= "After happy" (get-in result [:happy :next-title])))
          (is (= (get-in result [:happy :run-id])
                 (get-in result [:happy :outcome-by]))))
        (testing "a failed serving run stalls until retrying that same run"
          (is (= "failed" (get-in result [:failed :stall :phase])))
          (is (= "provider failed" (get-in result [:failed :stall :error])))
          (is (true? (get-in result [:failed :query-contains?])))
          (is (= [(get-in result [:failed :run-id])]
                 (get-in result [:failed :retry-run-ids])))
          (is (= "pending" (get-in result [:failed :phase-after-retry])))
          (is (= "pending" (get-in result [:failed :phase-after-scan])))
          (is (= "closed" (get-in result [:failed :gate-state])))
          (is (= "recovered" (get-in result [:failed :result]))))
        (testing "an invalid request is durable and creates no run"
          (is (str/includes? (get-in result [:missing :error])
                             "harness/alias"))
          (is (zero? (get-in result [:missing :run-count]))))))))

(deftest incomplete-spawns-are-adopted-retried-and-bounded
  (with-agent-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.executors.agent :as agent]
                         '[millhouse.spools.workflow :as workflow]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (harnesses/register-harness!
                 rt :fake
                 {:modes #{:headless}
                  :prepare 'ct.spools.harnesses/create!
                  :finish 'ct.spools.harnesses/finish!})
                (let [attr #(spool/attr-get %1 %2)
                      gate-by-title
                      (fn [title]
                        (first (weaver/list
                                rt
                                [:and [:= :title title]
                                 [:= [:attr "workflow/gate"] "agent"]]
                                {})))
                      runs-for-gate
                      (fn [gate-id]
                        (weaver/list
                         rt
                         [:and [:= [:attr "harness/run"] "true"]
                          [:edge/out "serves" [:= :id gate-id]]]
                         {}))
                      gate-definition
                      (fn [title]
                        (workflow/workflow
                         title
                         (workflow/gate
                          :delegate title :agent
                          :attributes {"harness/alias" "fake"
                                       "harness/prompt" "Do the work."})))
                      _ (workflow/start! "adopt-agent"
                                         (gate-definition "Adopt gate") {})
                      adopt-gate (gate-by-title "Adopt gate")
                      _ (weaver/update!
                         rt (:id adopt-gate)
                         {:attributes
                          {"agent-executor/spawn-attempt" 1
                           "agent-executor/spawn-session-id" "adopt-session"}})
                      orphan (harnesses/create!
                              rt {:harness "fake"
                                  :prompt "Do the work."
                                  :session-id "adopt-session"})
                      _ (agent/scan!)
                      adopted (weaver/show rt (:id orphan))
                      adopted-gate (weaver/show rt (:id adopt-gate))
                      _ (workflow/start! "resume-agent"
                                         (gate-definition "Resume gate") {})
                      resume-gate (gate-by-title "Resume gate")
                      _ (weaver/update!
                         rt (:id resume-gate)
                         {:attributes
                          {"agent-executor/spawn-attempt" 1
                           "agent-executor/spawn-session-id" "resume-session"}})
                      _ (agent/scan!)
                      resumed-run (first (runs-for-gate (:id resume-gate)))
                      resumed-gate (weaver/show rt (:id resume-gate))
                      failed-calls (atom 0)
                      _ (workflow/start! "bounded-agent"
                                         (gate-definition "Bounded gate") {})
                      bounded-gate (gate-by-title "Bounded gate")
                      _ (with-redefs
                         [harnesses/create!
                          (fn [& _]
                            (swap! failed-calls inc)
                            (throw (ex-info "synthetic create failure" {})))]
                          (agent/scan!))
                      bounded-gate (weaver/show rt (:id bounded-gate))]
                  {:adopted
                   {:run-id (:id adopted)
                    :workflow-run-id (attr adopted :workflow/run-id)
                    :serving-ids (mapv :id
                                       (runs-for-gate (:id adopt-gate)))
                    :attempt (attr adopted-gate
                                   :agent-executor/spawn-attempt)
                    :session (attr adopted-gate
                                   :agent-executor/spawn-session-id)}
                   :resumed
                   {:session (attr resumed-run :harness/session-id)
                    :workflow-run-id (attr resumed-run :workflow/run-id)
                    :serving-count (count (runs-for-gate (:id resume-gate)))
                    :attempt (attr resumed-gate
                                   :agent-executor/spawn-attempt)}
                   :bounded
                   {:calls @failed-calls
                    :error (attr bounded-gate :gate/error)
                    :attempt (attr bounded-gate
                                   :agent-executor/spawn-attempt)
                    :session (attr bounded-gate
                                   :agent-executor/spawn-session-id)}})))]
        (testing "a committed but unlinked run is adopted by session claim"
          (is (= (get-in result [:adopted :run-id])
                 (first (get-in result [:adopted :serving-ids]))))
          (is (= "adopt-agent"
                 (get-in result [:adopted :workflow-run-id])))
          (is (= 1 (get-in result [:adopted :attempt])))
          (is (nil? (get-in result [:adopted :session]))))
        (testing "a claim with no run resumes from its next attempt"
          (is (= "resume-session" (get-in result [:resumed :session])))
          (is (= "resume-agent"
                 (get-in result [:resumed :workflow-run-id])))
          (is (= 1 (get-in result [:resumed :serving-count])))
          (is (= 2 (get-in result [:resumed :attempt]))))
        (testing "three failed create attempts make the gate durably stalled"
          (is (= 3 (get-in result [:bounded :calls])))
          (is (str/includes? (get-in result [:bounded :error])
                             "failed after 3 attempts"))
          (is (= 3 (get-in result [:bounded :attempt])))
          (is (nil? (get-in result [:bounded :session]))))))))

(deftest executor-state-shape-matches-version
  (let [new-state (ns-resolve 'ct.spools.harnesses.executors.agent
                              'new-state)]
    (is (= #{:scan-monitor}
           (set (keys (new-state)))))))

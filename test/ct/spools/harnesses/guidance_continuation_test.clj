(ns ct.spools.harnesses.guidance-continuation-test
  "Native continuation, retry fencing, and explicit rollback tests."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-fixture :as guidance-fixture]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest native-resume-retry-and-explicit-legacy-rollback
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              guidance-fixture/interactive-selection
              '(binding [capability/*test-capability-profiles* [profile]
                         capability/*test-preflight-runner* accepted-runner]
                 (let [parent (harnesses/create!
                               rt {:harness :native-codex
                                   :mode :headless
                                   :cwd "/tmp"
                                   :prompt "Parent native task"
                                   :attributes
                                   {:harness/appended-system-prompts
                                    ["first" "first"]}
                                   :guidance-transport "native-v1"})
                       parent-start (harnesses/begin-attempt! rt (:id parent))
                       parent-bootstrap
                       (harnesses/managed-bootstrap rt (:id parent))
                       parent-bundle
                       (harnesses/managed-startup!
                        rt {:harness "codex"
                            :native-session-id "continued-session"
                            :cwd "/tmp"
                            :scope "root"
                            :bootstrap parent-bootstrap
                            :guidance (guidance/bootstrap
                                       (:strand parent-start))})
                       parent-receipt
                       (guidance-receipt parent-bundle "adapter-handoff")
                       _ (harnesses/guidance-acknowledge! rt parent-receipt)
                       parent-done
                       (harnesses/finish!
                        rt (:id parent)
                        {:status :done :exit-code 0 :result "done"
                         :session-id "continued-session"
                         :session-usable true
                         :invocation (:invocation parent-start)
                         :evidence {:settled true
                                    :settlement "process-exit"}})
                       child (harnesses/resume! rt (:id parent-done)
                                                {:prompt "Continue native task"})
                       child-template
                       (attr child :harness/guidance-context-template)
                       child-start (harnesses/begin-attempt! rt (:id child))
                       child-failure
                       (-> (guidance-receipt
                            (assoc parent-bundle
                                   :run-id (:id child)
                                   :attempt (:attempt child-start)
                                   :invocation (:invocation child-start)
                                   :bundle-sha256
                                   (attr child :harness/guidance-bundle-sha256)
                                   :capability-sha256
                                   (attr child
                                         :harness/guidance-capability-sha256))
                            "failed")
                           (dissoc "native-session-id")
                           (assoc "stage" "startup"
                                  "code" "adapter-failed"
                                  "diagnostic" "fixture failure"))
                       _ (harnesses/guidance-fail! rt child-failure)
                       _ (harnesses/settle-outcome!
                          rt (:id child)
                          {:status :failed :exit-code 1
                           :session-usable false
                           :invocation (:invocation child-start)}
                          {:settled true :settlement "process-exit"
                           :failure-class "bootstrap"})
                       retried (harnesses/retry! rt (:id child) {})
                       retry-start (harnesses/begin-attempt! rt (:id retried))
                       before-stale (weaver/show rt (:id child))
                       stale (harnesses/guidance-fail! rt child-failure)
                       after-stale (weaver/show rt (:id child))
                       retry-failure
                       (-> child-failure
                           (assoc "attempt" (:attempt retry-start)
                                  "invocation" (:invocation retry-start)))
                       _ (harnesses/guidance-fail! rt retry-failure)
                       _ (harnesses/settle-outcome!
                          rt (:id child)
                          {:status :failed :exit-code 1
                           :session-usable false
                           :invocation (:invocation retry-start)}
                          {:settled true :settlement "process-exit"
                           :failure-class "bootstrap"})
                       rolled-back
                       (harnesses/retry!
                        rt (:id child) {:guidance-transport "legacy"})
                       prepared (codex/prepare rt (codex/harness rt)
                                               rolled-back)]
                   {:parent-transport
                    (attr parent-done :harness/guidance-transport)
                    :child-transport
                    (attr child :harness/guidance-transport)
                    :same-session
                    (= (attr parent-done :harness/session-id)
                       (attr child :harness/session-id))
                    :same-template
                    (= (attr parent-done :harness/guidance-context-template)
                       child-template)
                    :retry-attempt (:attempt retry-start)
                    :retry-state
                    (get (last (guidance/attempt-records (:strand retry-start)))
                         "state")
                    :stale-result (get stale :result)
                    :stale-no-write (= before-stale after-stale)
                    :rollback-transport
                    (attr rolled-back :harness/guidance-transport)
                    :rollback-capability
                    (attr rolled-back :harness/guidance-capability)
                    :rollback-prompt
                    (some #(when (clojure.string/starts-with?
                                  % "developer_instructions=") %)
                          (:argv prepared))}))))]
        (is (= ["native-v1" "native-v1"]
               [(:parent-transport result) (:child-transport result)]))
        (is (true? (:same-session result)))
        (is (true? (:same-template result)))
        (is (= [2 "pending"]
               [(:retry-attempt result) (:retry-state result)]))
        (is (= "ignored" (:stale-result result)))
        (is (true? (:stale-no-write result)))
        (is (= "legacy" (:rollback-transport result)))
        (is (nil? (:rollback-capability result)))
        (is (string? (:rollback-prompt result)))))))

(deftest pre-reservation-pi-lineage-rejects-native-before-writes
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              guidance-fixture/interactive-selection
              '(do
                 (require '[ct.spools.harnesses.internal.managed-startup
                            :as managed]
                          '[ct.spools.harnesses.providers.pi :as pi]
                          '[millhouse.spools.identity :as identity]
                          '[millstrand.api.graph.alpha :as graph])
                 (harnesses/register-harness! rt :pi (pi/harness rt))
                 (let [root
                       (with-redefs [managed/managed-harness?
                                     (constantly false)]
                         (harnesses/create!
                          rt {:harness :pi :mode :interactive
                              :cwd "/tmp/pre-reservation-pi"
                              :session-id "legacy-pi-session"}))
                       root-start
                       (harnesses/begin-attempt! rt (:id root))
                       root
                       (harnesses/finish!
                        rt (:id root)
                        {:status :done :exit-code 0
                         :session-id "legacy-pi-session"
                         :session-usable true
                         :invocation (:invocation root-start)})
                       snapshot
                       (fn [run]
                         (let [current (weaver/show rt (:id run))
                               identity-strand
                               (identity/current rt (attr current :identity/id))]
                           {:strands (->> (weaver/list rt) (map :id) sort vec)
                            :run current
                            :identity identity-strand
                            :performed
                            (->> (graph/outgoing-edges
                                  rt [(:id identity-strand)] "performed")
                                 (sort-by pr-str)
                                 vec)}))
                       before-resume (snapshot root)
                       native-resume
                       (try
                         (harnesses/resume!
                          rt (:id root)
                          {:guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo error
                           (ex-message error)))
                       after-resume (snapshot root)
                       raw-native
                       (try
                         (harnesses/create!
                          rt {:harness :pi :mode :interactive
                              :cwd "/tmp/pre-reservation-pi"
                              :session-id "legacy-pi-session"
                              :resumes (:id root)
                              :guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo error
                           (ex-message error)))
                       after-raw (snapshot root)
                       child (harnesses/resume! rt (:id root) {})
                       child-start
                       (harnesses/begin-attempt! rt (:id child))
                       child
                       (harnesses/finish!
                        rt (:id child)
                        {:status :failed :exit-code 1 :error "retry"
                         :invocation (:invocation child-start)
                         :evidence {:settled true
                                    :settlement "process-exit"}})
                       before-retry (snapshot child)
                       native-retry
                       (try
                         (harnesses/retry!
                          rt (:id child)
                          {:guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo error
                           (ex-message error)))
                       after-retry (snapshot child)]
                   {:native-resume native-resume
                    :raw-native raw-native
                    :native-retry native-retry
                    :resume-no-write (= before-resume after-resume)
                    :raw-no-write (= before-resume after-raw)
                    :retry-no-write (= before-retry after-retry)
                    :child-transport
                    (attr child :harness/guidance-transport)
                    :child-template
                    (attr child :harness/guidance-context-template)
                    :child-reservation
                    (attr child :identity/reservation-id)}))))]
        (doseq [failure [(:native-resume result)
                         (:raw-native result)
                         (:native-retry result)]]
          (is (re-find #"Pre-reservation Pi continuations require legacy"
                       failure)))
        (is (true? (:resume-no-write result)))
        (is (true? (:raw-no-write result)))
        (is (true? (:retry-no-write result)))
        (is (= "legacy" (:child-transport result)))
        (is (map? (:child-template result)))
        (is (nil? (:child-reservation result)))))))

(deftest native-interactive-is-rejected-before-writes-and-at-execution
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              guidance-fixture/interactive-selection
              '(binding [capability/*test-capability-profiles* [profile]
                         capability/*test-preflight-runner* accepted-runner]
                 (let [preflight-calls (atom 0)
                       counting-runner
                       (fn [accepted request]
                         (swap! preflight-calls inc)
                         (accepted-runner accepted request))
                       fresh
                       (binding [capability/*test-preflight-runner*
                                 counting-runner]
                         (mapv
                          (fn [provider]
                            (let [before (weaver/list rt)
                                  error
                                  (try
                                    (harnesses/create!
                                     rt {:harness provider
                                         :mode :interactive
                                         :cwd "/tmp"
                                         :guidance-transport "native-v1"})
                                    nil
                                    (catch clojure.lang.ExceptionInfo failure
                                      (ex-message failure)))]
                              {:error error
                               :no-write (= before (weaver/list rt))}))
                          [:native-codex :native-pi]))
                       legacy-default
                       (harnesses/create!
                        rt {:harness :codex :mode :interactive :cwd "/tmp"})
                       legacy-explicit
                       (harnesses/create!
                        rt {:harness :pi :mode :interactive :cwd "/tmp"
                            :guidance-transport "legacy"})
                       headless
                       (harnesses/create!
                        rt {:harness :native-codex :mode :headless
                            :cwd "/tmp" :prompt "Headless native remains admitted"
                            :guidance-transport "native-v1"})
                       completed
                       (with-native-interactive-fixture
                         (fn []
                           (let [run
                                 (harnesses/create!
                                  rt {:harness :native-codex
                                      :mode :interactive :cwd "/tmp"
                                      :guidance-transport "native-v1"})
                                 started
                                 (harnesses/begin-attempt! rt (:id run))
                                 bundle
                                 (harnesses/managed-startup!
                                  rt {:harness "codex"
                                      :native-session-id "interactive-completed"
                                      :cwd "/tmp" :scope "root"
                                      :bootstrap
                                      (harnesses/managed-bootstrap rt (:id run))
                                      :guidance
                                      (guidance/bootstrap (:strand started))})
                                 _ (harnesses/guidance-acknowledge!
                                    rt (guidance-receipt bundle
                                                         "adapter-handoff"))]
                             (harnesses/finish!
                              rt (:id run)
                              {:status :done :exit-code 0
                               :session-id "interactive-completed"
                               :session-usable true
                               :invocation (:invocation started)
                               :evidence {:settled true
                                          :settlement "process-exit"}}))))
                       _ (reset! preflight-calls 0)
                       before-resume (weaver/list rt)
                       resume-error
                       (binding [capability/*test-preflight-runner*
                                 counting-runner]
                         (try
                           (harnesses/resume! rt (:id completed) {})
                           nil
                           (catch clojure.lang.ExceptionInfo failure
                             (ex-message failure))))
                       after-resume (weaver/list rt)
                       failed
                       (with-native-interactive-fixture
                         (fn []
                           (let [run
                                 (harnesses/create!
                                  rt {:harness :native-codex
                                      :mode :interactive :cwd "/tmp"
                                      :guidance-transport "native-v1"})
                                 started
                                 (harnesses/begin-attempt! rt (:id run))
                                 receipt
                                 {"schema" "millstrand.agent-guidance-receipt/v1"
                                  "run-id" (:id run)
                                  "attempt" (:attempt started)
                                  "invocation" (:invocation started)
                                  "harness" "codex"
                                  "transport" "native-v1"
                                  "bundle-sha256"
                                  (attr run :harness/guidance-bundle-sha256)
                                  "capability-sha256"
                                  (attr run :harness/guidance-capability-sha256)
                                  "outcome" "failed"
                                  "stage" "rendering"
                                  "code" "fixture-failure"
                                  "diagnostic" "fixture failure"}
                                 _ (harnesses/guidance-fail! rt receipt)]
                             (harnesses/settle-outcome!
                              rt (:id run)
                              {:status :failed :exit-code 1
                               :session-usable false
                               :invocation (:invocation started)}
                              {:settled true :settlement "process-exit"
                               :failure-class "bootstrap"}))))
                       _ (reset! preflight-calls 0)
                       before-retry (weaver/show rt (:id failed))
                       retry-error
                       (binding [capability/*test-preflight-runner*
                                 counting-runner]
                         (try
                           (harnesses/retry! rt (:id failed) {})
                           nil
                           (catch clojure.lang.ExceptionInfo failure
                             (ex-message failure))))
                       after-retry (weaver/show rt (:id failed))
                       queued
                       (with-native-interactive-fixture
                         #(harnesses/create!
                           rt {:harness :native-codex :mode :interactive
                               :cwd "/tmp"
                               :guidance-transport "native-v1"}))
                       _ (reset! preflight-calls 0)
                       queued-error
                       (binding [capability/*test-preflight-runner*
                                 counting-runner]
                         (try
                           (harnesses/begin-attempt! rt (:id queued))
                           nil
                           (catch clojure.lang.ExceptionInfo failure
                             (ex-message failure))))
                       queued-after (weaver/show rt (:id queued))]
                   {:fresh fresh
                    :legacy [(attr legacy-default :harness/guidance-transport)
                             (attr legacy-explicit :harness/guidance-transport)]
                    :headless (attr headless :harness/guidance-transport)
                    :resume-error resume-error
                    :resume-no-write (= before-resume after-resume)
                    :retry-error retry-error
                    :retry-no-write (= before-retry after-retry)
                    :queued-error queued-error
                    :queued [(attr queued-after :harness/status)
                             (attr queued-after :harness/substatus)
                             (attr queued-after :harness/settled)]
                    :preflight-calls @preflight-calls}))))]
        (is (every? #(re-find #"does not support interactive" (:error %))
                    (:fresh result)))
        (is (every? :no-write (:fresh result)))
        (is (= ["legacy" "legacy"] (:legacy result)))
        (is (= "native-v1" (:headless result)))
        (is (re-find #"does not support interactive" (:resume-error result)))
        (is (true? (:resume-no-write result)))
        (is (re-find #"does not support interactive" (:retry-error result)))
        (is (true? (:retry-no-write result)))
        (is (re-find #"does not support interactive" (:queued-error result)))
        (is (= ["failed" "bootstrap" "true"] (:queued result)))
        (is (zero? (:preflight-calls result)))))))

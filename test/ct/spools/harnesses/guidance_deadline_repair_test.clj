(ns ct.spools.harnesses.guidance-deadline-repair-test
  "Execution-resource fencing for recovered native guidance deadlines."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-fixture :as guidance-fixture]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest early-callback-rearms-and-old-generation-stays-shut-down
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
                 (with-native-interactive-fixture
                   (fn []
                     (let [arm!
                           (deref
                            (ns-resolve
                             'ct.spools.harnesses.execution
                             'arm-guidance-deadline!))
                           execution-state
                           (deref
                            (ns-resolve
                             'ct.spools.harnesses.execution
                             'state))
                           fractional
                           (harnesses/create!
                            rt {:harness :native-codex :mode :interactive
                                :cwd "/tmp"
                                :guidance-transport "native-v1"})
                           fractional-start
                           (harnesses/begin-attempt! rt (:id fractional))
                           fractional-record
                           (guidance/current-attempt (:strand fractional-start))
                           fractional-deadline
                           (str (.plusNanos (java.time.Instant/now) 150500000))
                           fractional
                           (weaver/update!
                            rt (:id fractional)
                            {:attributes
                             {:harness/guidance-attempts
                              [(assoc fractional-record "deadline-at"
                                      fractional-deadline)]}})
                           _ (execution/open-execution! {:runtime rt})
                           generation (:generation (execution-state rt))
                           early-result (arm! rt fractional generation)
                           _ (Thread/sleep 300)
                           fractional-after
                           (weaver/show rt (:id fractional))
                           _ (execution/close-execution! {:runtime rt})
                           reopened
                           (harnesses/create!
                            rt {:harness :native-codex :mode :interactive
                                :cwd "/tmp"
                                :guidance-transport "native-v1"})
                           reopened-start
                           (harnesses/begin-attempt! rt (:id reopened))
                           reopened-record
                           (guidance/current-attempt (:strand reopened-start))
                           reopened
                           (weaver/update!
                            rt (:id reopened)
                            {:attributes
                             {:harness/guidance-attempts
                              [(assoc reopened-record "deadline-at"
                                      (str (.plusMillis
                                            (java.time.Instant/now) 180)))]}})
                           _ (execution/open-execution! {:runtime rt})
                           old-generation (:generation (execution-state rt))
                           _ (execution/close-execution! {:runtime rt})
                           old-callback-result
                           (arm! rt reopened old-generation)
                           _ (Thread/sleep 250)
                           while-closed (weaver/show rt (:id reopened))
                           _ (execution/open-execution! {:runtime rt})
                           after-reopen (weaver/show rt (:id reopened))
                           _ (execution/close-execution! {:runtime rt})]
                       {:early-result early-result
                        :fractional
                        [(attr fractional-after :harness/status)
                         (attr fractional-after :harness/substatus)]
                        :old-callback-result old-callback-result
                        :while-closed
                        [(attr while-closed :harness/status)
                         (get (guidance/current-attempt while-closed) "state")]
                        :after-reopen
                        [(attr after-reopen :harness/status)
                         (attr after-reopen :harness/substatus)]}))))))]
        (is (= :scheduled (:early-result result)))
        (is (= ["failed" "bootstrap"] (:fractional result)))
        (is (nil? (:old-callback-result result)))
        (is (= ["running" "pending"] (:while-closed result)))
        (is (= ["failed" "bootstrap"] (:after-reopen result)))))))

(deftest early-callbacks-respect-acknowledgement-completion-and-retry
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              guidance-fixture/interactive-selection
              '(do
                 (require '[ct.spools.harnesses.internal.guidance-receipts
                            :as receipts])
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* accepted-runner]
                   (with-native-interactive-fixture
                     (fn []
                       (let [arm!
                             (deref
                              (ns-resolve
                               'ct.spools.harnesses.execution
                               'arm-guidance-deadline!))
                             execution-state
                             (deref
                              (ns-resolve
                               'ct.spools.harnesses.execution
                               'state))
                             acknowledged
                             (harnesses/create!
                              rt {:harness :native-codex :mode :interactive
                                  :cwd "/tmp"
                                  :guidance-transport "native-v1"})
                             acknowledged-start
                             (harnesses/begin-attempt! rt (:id acknowledged))
                             acknowledged-record
                             (guidance/current-attempt
                              (:strand acknowledged-start))
                             acknowledged
                             (weaver/update!
                              rt (:id acknowledged)
                              {:attributes
                               {:harness/guidance-attempts
                                [(assoc acknowledged-record "deadline-at"
                                        (str (.plusMillis
                                              (java.time.Instant/now) 250)))]}})
                             _ (execution/open-execution! {:runtime rt})
                             generation (:generation (execution-state rt))
                             _ (arm! rt acknowledged generation)
                             bundle
                             (harnesses/managed-startup!
                              rt {:harness "codex"
                                  :native-session-id "deadline-ack-session"
                                  :cwd "/tmp" :scope "root"
                                  :bootstrap
                                  (harnesses/managed-bootstrap
                                   rt (:id acknowledged))
                                  :guidance
                                  (guidance/bootstrap
                                   (:strand acknowledged-start))})
                             _ (harnesses/guidance-acknowledge!
                                rt (guidance-receipt bundle "adapter-handoff"))
                             _ (Thread/sleep 300)
                             acknowledged-after
                             (weaver/show rt (:id acknowledged))
                             completed
                             (harnesses/create!
                              rt {:harness :native-codex :mode :interactive
                                  :cwd "/tmp"
                                  :guidance-transport "native-v1"})
                             completed-start
                             (harnesses/begin-attempt! rt (:id completed))
                             completed-record
                             (guidance/current-attempt (:strand completed-start))
                             completed
                             (weaver/update!
                              rt (:id completed)
                              {:attributes
                               {:harness/guidance-attempts
                                [(assoc completed-record "deadline-at"
                                        (str (.plusMillis
                                              (java.time.Instant/now) 250)))]}})
                             _ (arm! rt completed generation)
                             completed
                             (harnesses/finish!
                              rt (:id completed)
                              {:status :failed :exit-code 1
                               :session-usable false
                               :invocation (:invocation completed-start)
                               :evidence {:settled false
                                          :settlement
                                          "no-terminal-evidence"}})
                             completed-before (weaver/show rt (:id completed))
                             _ (Thread/sleep 300)
                             completed-after (weaver/show rt (:id completed))
                             retried-origin
                             (harnesses/create!
                              rt {:harness :native-codex :mode :interactive
                                  :cwd "/tmp"
                                  :guidance-transport "native-v1"})
                             retried-start
                             (harnesses/begin-attempt! rt (:id retried-origin))
                             retried-origin (:strand retried-start)
                             failure
                             {"schema" "millstrand.agent-guidance-receipt/v1"
                              "run-id" (:id retried-origin)
                              "attempt" (:attempt retried-start)
                              "invocation" (:invocation retried-start)
                              "harness" "codex"
                              "transport" "native-v1"
                              "bundle-sha256"
                              (attr retried-origin :harness/guidance-bundle-sha256)
                              "capability-sha256"
                              (attr retried-origin
                                    :harness/guidance-capability-sha256)
                              "outcome" "failed"
                              "stage" "rendering"
                              "code" "fixture-failure"
                              "diagnostic" "fixture failure"}
                             _ (harnesses/guidance-fail! rt failure)
                             _ (harnesses/settle-outcome!
                                rt (:id retried-origin)
                                {:status :failed :exit-code 1
                                 :session-usable false
                                 :invocation (:invocation retried-start)}
                                {:settled true :settlement "process-exit"
                                 :failure-class "bootstrap"})
                             retried (harnesses/retry! rt (:id retried-origin) {})
                             retry-start
                             (harnesses/begin-attempt! rt (:id retried))
                             retry-before (weaver/show rt (:id retried))
                             stale-result (arm! rt retried-origin generation)
                             retry-after (weaver/show rt (:id retried))
                             _ (execution/close-execution! {:runtime rt})]
                         {:acknowledged
                          [(attr acknowledged-after :harness/status)
                           (get (guidance/current-attempt acknowledged-after)
                                "state")]
                          :completed-no-write
                          (= completed-before completed-after)
                          :completed
                          [(attr completed :harness/status)
                           (attr completed :harness/substatus)]
                          :stale-result stale-result
                          :retry-attempt (:attempt retry-start)
                          :retry-no-write (= retry-before retry-after)})))))))]
        (is (= ["running" "acknowledged"] (:acknowledged result)))
        (is (true? (:completed-no-write result)))
        (is (= ["failed" "bootstrap"] (:completed result)))
        (is (nil? (:stale-result result)))
        (is (= 2 (:retry-attempt result)))
        (is (true? (:retry-no-write result)))))))

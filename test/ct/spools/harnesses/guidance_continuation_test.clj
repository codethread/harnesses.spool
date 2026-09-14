(ns ct.spools.harnesses.guidance-continuation-test
  "Native continuation, retry fencing, and explicit rollback tests."
  (:require [clojure.test :refer [deftest is]]
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
              '(binding [capability/*test-capability-profiles* [profile]
                         capability/*test-preflight-runner* accepted-runner]
                 (let [parent (harnesses/create!
                               rt {:harness :native-codex
                                   :mode :interactive
                                   :cwd "/tmp"
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
                       child (harnesses/resume! rt (:id parent-done) {})
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

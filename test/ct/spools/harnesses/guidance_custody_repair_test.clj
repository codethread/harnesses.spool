(ns ct.spools.harnesses.guidance-custody-repair-test
  "Custody enforcement for completion-induced native bootstrap failures."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest completion-failure-keeps-custody-and-durable-stop-intent
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(do
                 (require '[ct.spools.harnesses.internal.process-custody
                            :as custody])
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* accepted-runner]
                   (let [finish-unacknowledged!
                         (fn [label fetch?]
                           (let [run
                                 (harnesses/create!
                                  rt {:harness :native-codex :mode :headless
                                      :cwd "/tmp" :prompt label
                                      :guidance-transport "native-v1"})
                                 reservation (attr run :identity/reservation-id)
                                 started
                                 (harnesses/begin-attempt! rt (:id run))
                                 _ (when fetch?
                                     (harnesses/managed-startup!
                                      rt {:harness "codex"
                                          :native-session-id
                                          (str label "-session")
                                          :cwd "/tmp" :scope "root"
                                          :bootstrap
                                          (harnesses/managed-bootstrap
                                           rt (:id run))
                                          :guidance
                                          (guidance/bootstrap
                                           (:strand started))}))
                                 finished
                                 (with-redefs
                                  [harnesses/stop!
                                   (fn [& _]
                                     (throw
                                      (ex-info
                                       "ordinary stop must not be called" {})))]
                                   (harnesses/finish!
                                    rt (:id run)
                                    {:status :failed :exit-code 1
                                     :session-usable false
                                     :invocation (:invocation started)
                                     :evidence
                                     {:settled false
                                      :settlement
                                      "no-terminal-evidence"}}))]
                             {:run finished
                              :reservation reservation
                              :invocation (:invocation started)}))
                         pending (finish-unacknowledged! "pending" false)
                         fetched (finish-unacknowledged! "fetched" true)
                         pending-run (:run pending)
                         custody-record
                         {:owner custody/owner
                          :key (custody/process-key
                                (:id pending-run)
                                (attr pending-run :harness/attempt))
                          :handle "owned-handle"
                          :phase :running}
                         pending-run
                         (weaver/update!
                          rt (:id pending-run)
                          {:attributes
                           (custody/durable-attributes
                            "harness" (:id pending-run)
                            (attr pending-run :harness/attempt)
                            custody-record)})
                         cancellations (atom [])
                         _ (execution/open-execution! {:runtime rt})
                         _ (with-redefs
                            [custody/list-owned (fn [_] [custody-record])
                             custody/cancel!
                             (fn [_ record]
                               (swap! cancellations conj (:handle record)))]
                             (execution/inspect-owned! rt))
                         _ (execution/close-execution! {:runtime rt})
                         after-cancellation
                         (weaver/show rt (:id pending-run))
                         settled
                         (harnesses/settle-outcome!
                          rt (:id pending-run)
                          {:status :failed :exit-code 1
                           :session-usable false
                           :invocation (:invocation pending)}
                          {:settled true :settlement "process-exit"
                           :failure-class "bootstrap"})
                         projection
                         (fn [entry]
                           (let [run (guidance/validation-run rt (:run entry))
                                 record (guidance/current-attempt run)]
                             {:status [(attr run :harness/status)
                                       (attr run :harness/substatus)
                                       (attr run :harness/settled)
                                       (attr run :harness/session-usable)]
                              :failure (get record "failure")
                              :stop [(attr run :harness/stop-requested-at)
                                     (attr run :harness/stop-reason)]
                              :reservation-preserved
                              (= (:reservation entry)
                                 (attr run :identity/reservation-id))
                              :attached (attr run :harness/native-attached)
                              :session (attr run :harness/session-id)}))]
                     {:pending (projection pending)
                      :fetched (projection fetched)
                      :cancellations @cancellations
                      :after-cancellation
                      [(attr after-cancellation :harness/status)
                       (attr after-cancellation :harness/substatus)
                       (attr after-cancellation :harness/settled)]
                      :settled
                      [(attr settled :harness/status)
                       (attr settled :harness/substatus)
                       (attr settled :harness/settled)
                       (attr settled :harness/settlement)]})))))]
        (doseq [projection [(:pending result) (:fetched result)]]
          (is (= ["failed" "bootstrap" "false" "false"]
                 (:status projection)))
          (is (= ["handoff" "missing-acknowledgement"]
                 [(get-in projection [:failure "stage"])
                  (get-in projection [:failure "code"])]))
          (is (string? (first (:stop projection))))
          (is (= "native guidance bootstrap failed"
                 (second (:stop projection))))
          (is (true? (:reservation-preserved projection))))
        (is (= "false" (get-in result [:pending :attached])))
        (is (= ["true" "fetched-session"]
               [(get-in result [:fetched :attached])
                (get-in result [:fetched :session])]))
        (is (= ["owned-handle"] (:cancellations result)))
        (is (= ["failed" "bootstrap" "false"]
               (:after-cancellation result)))
        (is (= ["failed" "bootstrap" "true" "process-exit"]
               (:settled result)))))))

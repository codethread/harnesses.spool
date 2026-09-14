(ns ct.spools.harnesses.guidance-repair-test
  "Concurrency, prelaunch, and recovery regressions for native guidance."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-fixture :as guidance-fixture]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(defn- eval-guidance-world [ctx body]
  (test-alpha/repl! ctx (list 'do guidance-test/lifecycle-setup
                              guidance-fixture/interactive-selection body)))

(deftest acknowledged-reconstruction-failure-is-sticky-and-preserves-attachment
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (eval-guidance-world
             ctx
             '(do
                (require '[ct.spools.harnesses.internal.guidance-receipts
                           :as receipts]
                         '[millhouse.spools.identity :as identity]
                         '[millstrand.api.graph.alpha :as graph])
                (binding [capability/*test-capability-profiles* [profile]
                          capability/*test-preflight-runner* accepted-runner]
                  (let [run (harnesses/create!
                             rt {:harness :native-codex
                                 :mode :headless
                                 :cwd "/tmp"
                                 :prompt "Acknowledged repair fixture"
                                 :guidance-transport "native-v1"})
                        started (harnesses/begin-attempt! rt (:id run))
                        bundle
                        (harnesses/managed-startup!
                         rt {:harness "codex"
                             :native-session-id "reconstructed-session"
                             :cwd "/tmp"
                             :scope "root"
                             :bootstrap
                             (harnesses/managed-bootstrap rt (:id run))
                             :guidance (guidance/bootstrap (:strand started))})
                        acknowledgement
                        (guidance-receipt bundle "adapter-handoff")
                        _ (harnesses/guidance-acknowledge!
                           rt acknowledgement)
                        malformed-receipt
                        (str (subs (strict-json/canonical-json acknowledgement)
                                   0
                                   (dec (count (strict-json/canonical-json
                                                acknowledgement))))
                             ",}")
                        trailing-comma
                        (try
                          (harnesses/guidance-acknowledge!
                           rt malformed-receipt)
                          nil
                          (catch clojure.lang.ExceptionInfo error
                            (ex-message error)))
                        failure
                        (assoc (guidance-receipt bundle "failed")
                               "stage" "rendering"
                               "code" "reconstruction-failed"
                               "diagnostic" "clear reconstruction failed")
                        recorded (harnesses/guidance-fail! rt failure)
                        before-replay (weaver/show rt (:id run))
                        replayed (harnesses/guidance-fail! rt failure)
                        after-replay (weaver/show rt (:id run))
                        late-ack
                        (harnesses/guidance-acknowledge! rt acknowledgement)
                        failed (weaver/show rt (:id run))
                        identity-strand
                        (identity/current rt (attr failed :identity/id))
                        completed-run
                        (harnesses/create!
                         rt {:harness :native-codex :mode :headless
                             :cwd "/tmp" :prompt "Completed receipt fixture"
                             :guidance-transport "native-v1"})
                        completed-start
                        (harnesses/begin-attempt! rt (:id completed-run))
                        completed-bundle
                        (harnesses/managed-startup!
                         rt {:harness "codex"
                             :native-session-id "completed-session"
                             :cwd "/tmp" :scope "root"
                             :bootstrap
                             (harnesses/managed-bootstrap rt (:id completed-run))
                             :guidance
                             (guidance/bootstrap (:strand completed-start))})
                        completed-ack
                        (guidance-receipt completed-bundle "adapter-handoff")
                        _ (harnesses/guidance-acknowledge! rt completed-ack)
                        completed
                        (harnesses/finish!
                         rt (:id completed-run)
                         {:status :done :exit-code 0 :result "done"
                          :session-id "completed-session"
                          :session-usable true
                          :invocation (:invocation completed-start)
                          :evidence {:settled true
                                     :settlement "process-exit"}})
                        completed-before (weaver/show rt (:id completed))
                        completed-failure
                        (harnesses/guidance-fail!
                         rt (assoc (guidance-receipt
                                    completed-bundle "failed")
                                   "stage" "rendering"
                                   "code" "late-reconstruction"
                                   "diagnostic" "late failure"))
                        completed-after (weaver/show rt (:id completed))]
                    {:trailing-comma trailing-comma
                     :recorded (get recorded :result)
                     :replayed (get replayed :result)
                     :late-ack (get late-ack :result)
                     :replay-no-write (= before-replay after-replay)
                     :status (attr failed :harness/status)
                     :substatus (attr failed :harness/substatus)
                     :stop-reason (attr failed :harness/stop-reason)
                     :settled (attr failed :harness/settled)
                     :attached (attr failed :harness/native-attached)
                     :session (attr failed :harness/session-id)
                     :identity-session
                     (attr identity-strand :identity/native-session-id)
                     :performed
                     (set (map :to_strand_id
                               (graph/outgoing-edges
                                rt [(:id identity-strand)] "performed")))
                     :completed-failure (get completed-failure :result)
                     :completed-no-write
                     (= completed-before completed-after)}))))]
        (is (re-find #"trailing comma" (:trailing-comma result)))
        (is (= ["recorded" "replayed" "ignored"]
               [(:recorded result) (:replayed result) (:late-ack result)]))
        (is (true? (:replay-no-write result)))
        (is (= ["failed" "bootstrap"
                "native guidance bootstrap failed" "false"]
               [(:status result) (:substatus result) (:stop-reason result)
                (:settled result)]))
        (is (= ["true" "reconstructed-session" "reconstructed-session"]
               [(:attached result) (:session result)
                (:identity-session result)]))
        (is (= 1 (count (:performed result))))
        (is (= "ignored" (:completed-failure result)))
        (is (true? (:completed-no-write result)))))))

(deftest expiry-reloads-and-fences-every-transition
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (eval-guidance-world
             ctx
             '(do
                (require '[ct.spools.harnesses.internal.guidance-receipts
                           :as receipts])
                (binding [capability/*test-capability-profiles* [profile]
                          capability/*test-preflight-runner* accepted-runner]
                  (let [past "2026-09-14T00:00:00Z"
                        set-deadline
                        (fn [run deadline]
                          (let [record (guidance/current-attempt run)]
                            (weaver/update!
                             rt (:id run)
                             {:attributes
                              {:harness/guidance-attempts
                               (mapv #(if (= record %)
                                        (assoc % "deadline-at" deadline)
                                        %)
                                     (guidance/attempt-records run))}})))
                        acknowledged-run
                        (harnesses/create!
                         rt {:harness :native-codex :mode :headless
                             :cwd "/tmp" :prompt "Acknowledged expiry fixture"
                             :guidance-transport "native-v1"})
                        acknowledged-start
                        (harnesses/begin-attempt! rt (:id acknowledged-run))
                        stale-origin
                        (update-in
                         (:strand acknowledged-start)
                         [:attributes :harness/guidance-attempts]
                         #(mapv (fn [record]
                                  (assoc record "deadline-at" past))
                                %))
                        bundle
                        (harnesses/managed-startup!
                         rt {:harness "codex"
                             :native-session-id "expiry-ack-session"
                             :cwd "/tmp" :scope "root"
                             :bootstrap
                             (harnesses/managed-bootstrap
                              rt (:id acknowledged-run))
                             :guidance
                             (guidance/bootstrap (:strand acknowledged-start))})
                        _ (harnesses/guidance-acknowledge!
                           rt (guidance-receipt bundle "adapter-handoff"))
                        before-ack-expiry
                        (weaver/show rt (:id acknowledged-run))
                        _ (receipts/expire! rt stale-origin)
                        after-ack-expiry
                        (weaver/show rt (:id acknowledged-run))
                        completed
                        (harnesses/finish!
                         rt (:id acknowledged-run)
                         {:status :done :exit-code 0 :result "done"
                          :session-id "expiry-ack-session"
                          :session-usable true
                          :invocation (:invocation acknowledged-start)
                          :evidence {:settled true
                                     :settlement "process-exit"}})
                        _ (receipts/expire! rt stale-origin)
                        after-completed-expiry
                        (weaver/show rt (:id acknowledged-run))
                        expiring (harnesses/create!
                                  rt {:harness :native-codex
                                      :mode :headless :cwd "/tmp"
                                      :prompt "Expiring fixture"
                                      :guidance-transport "native-v1"})
                        expiring-start
                        (harnesses/begin-attempt! rt (:id expiring))
                        expired-origin
                        (set-deadline (:strand expiring-start) past)
                        expired (receipts/expire! rt expired-origin)
                        _ (harnesses/settle-outcome!
                           rt (:id expired)
                           {:status :failed :exit-code 1
                            :session-usable false
                            :invocation (:invocation expiring-start)}
                           {:settled true :settlement "process-exit"
                            :failure-class "bootstrap"})
                        retried (harnesses/retry! rt (:id expired) {})
                        retry-start
                        (harnesses/begin-attempt! rt (:id retried))
                        before-retry-expiry (weaver/show rt (:id retried))
                        _ (receipts/expire! rt expired-origin)
                        after-retry-expiry (weaver/show rt (:id retried))]
                    {:acknowledged-no-write
                     (= before-ack-expiry after-ack-expiry)
                     :completed-no-write
                     (= completed after-completed-expiry)
                     :expired
                     [(attr expired :harness/status)
                      (attr expired :harness/substatus)
                      (attr expired :harness/stop-reason)]
                     :retry-attempt (:attempt retry-start)
                     :retry-no-write
                     (= before-retry-expiry after-retry-expiry)}))))]
        (is (true? (:acknowledged-no-write result)))
        (is (true? (:completed-no-write result)))
        (is (= ["failed" "bootstrap"
                "native guidance handoff timed out"]
               (:expired result)))
        (is (= 2 (:retry-attempt result)))
        (is (true? (:retry-no-write result)))))))

(deftest prelaunch-failure-and-persisted-interactive-deadline-recovery
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (eval-guidance-world
             ctx
             '(do
                (require '[ct.spools.harnesses.internal.guidance-receipts
                           :as receipts])
                (binding [capability/*test-capability-profiles* [profile]
                          capability/*test-preflight-runner* accepted-runner]
                  (let [prelaunch
                        (create-native-interactive-fixture!
                         rt {:harness :native-codex :mode :interactive
                             :cwd "/tmp" :guidance-transport "native-v1"})
                        preparation-error
                        (with-redefs
                         [codex/prepare
                          (fn [& _]
                            (throw (ex-info "verified preparation failure" {})))]
                          (try
                            (execution/prepare-interactive! rt prelaunch)
                            nil
                            (catch clojure.lang.ExceptionInfo error
                              (ex-message error))))
                        prelaunch-failed (weaver/show rt (:id prelaunch))
                        positive
                        (create-native-interactive-fixture!
                         rt {:harness :native-codex :mode :interactive
                             :cwd "/tmp" :guidance-transport "native-v1"})
                        positive-before (weaver/show rt (:id positive))
                        positive-error
                        (try
                          (harnesses/finish!
                           rt (:id positive)
                           {:status :done :exit-code 0
                            :session-usable false})
                          nil
                          (catch clojure.lang.ExceptionInfo error
                            (ex-message error)))
                        positive-after (weaver/show rt (:id positive))
                        unknown
                        (create-native-interactive-fixture!
                         rt {:harness :native-codex :mode :interactive
                             :cwd "/tmp" :guidance-transport "native-v1"})
                        unknown
                        (harnesses/finish!
                         rt (:id unknown)
                         {:status :failed :error "unknown custody"})
                        malformed
                        (create-native-interactive-fixture!
                         rt {:harness :native-codex :mode :interactive
                             :cwd "/tmp" :guidance-transport "native-v1"})
                        malformed-start
                        (begin-native-interactive-fixture! rt (:id malformed))
                        _ (weaver/update!
                           rt (:id malformed)
                           {:attributes {:harness/guidance-attempts []}})
                        malformed-before (weaver/show rt (:id malformed))
                        malformed-error
                        (try
                          (harnesses/finish!
                           rt (:id malformed)
                           {:status :failed :error "active corruption"
                            :invocation (:invocation malformed-start)})
                          nil
                          (catch clojure.lang.ExceptionInfo error
                            (ex-message error)))
                        malformed-after (weaver/show rt (:id malformed))
                        recoverable
                        (create-native-interactive-fixture!
                         rt {:harness :native-codex :mode :interactive
                             :cwd "/tmp" :guidance-transport "native-v1"})
                        recoverable-start
                        (begin-native-interactive-fixture! rt (:id recoverable))
                        deadline
                        (str (.plusMillis (java.time.Instant/now) 1000))
                        record
                        (guidance/current-attempt (:strand recoverable-start))
                        recoverable
                        (weaver/update!
                         rt (:id recoverable)
                         {:attributes
                          {:harness/guidance-attempts
                           [(assoc record "deadline-at" deadline)]}})
                        _ (execution/open-execution! {:runtime rt})
                        _ (execution/close-execution! {:runtime rt})
                        fetched-codex
                        (create-native-interactive-fixture!
                         rt {:harness :native-codex :mode :interactive
                             :cwd "/tmp" :guidance-transport "native-v1"})
                        fetched-codex-start
                        (begin-native-interactive-fixture! rt (:id fetched-codex))
                        _ (harnesses/managed-startup!
                           rt {:harness "codex"
                               :native-session-id "recovery-codex-session"
                               :cwd "/tmp" :scope "root"
                               :bootstrap
                               (harnesses/managed-bootstrap
                                rt (:id fetched-codex))
                               :guidance
                               (guidance/bootstrap
                                (:strand fetched-codex-start))})
                        fetched-codex
                        (let [current (weaver/show rt (:id fetched-codex))
                              record (guidance/current-attempt current)]
                          (weaver/update!
                           rt (:id current)
                           {:attributes
                            {:harness/guidance-attempts
                             [(assoc record "deadline-at"
                                     "2026-09-14T00:00:00Z")]}}))
                        fetched-pi
                        (create-native-interactive-fixture!
                         rt {:harness :native-codex :mode :interactive
                             :cwd "/tmp" :guidance-transport "native-v1"})
                        fetched-pi-start
                        (begin-native-interactive-fixture! rt (:id fetched-pi))
                        _ (harnesses/managed-startup!
                           rt {:harness "codex"
                               :native-session-id "recovery-pi-session"
                               :cwd "/tmp" :scope "root"
                               :bootstrap
                               (harnesses/managed-bootstrap rt (:id fetched-pi))
                               :guidance
                               (guidance/bootstrap (:strand fetched-pi-start))})
                        fetched-pi
                        (let [current (weaver/show rt (:id fetched-pi))
                              record (guidance/current-attempt current)]
                          (weaver/update!
                           rt (:id current)
                           {:attributes
                            {:harness/harness "pi"
                             :harness/guidance-attempts
                             [(assoc record "deadline-at"
                                     "2026-09-14T00:00:00Z")]}}))
                        _ (Thread/sleep 1100)
                        _ (execution/open-execution! {:runtime rt})
                        recovered (weaver/show rt (:id recoverable))
                        codex-after (weaver/show rt (:id fetched-codex))
                        pi-after (weaver/show rt (:id fetched-pi))
                        _ (execution/close-execution! {:runtime rt})]
                    {:preparation-error preparation-error
                     :prelaunch
                     [(attr prelaunch-failed :harness/status)
                      (attr prelaunch-failed :harness/settled)
                      (attr prelaunch-failed :harness/settlement)
                      (attr prelaunch-failed :harness/native-attached)
                      (guidance/attempt-records prelaunch-failed)]
                     :positive-error positive-error
                     :positive-no-write (= positive-before positive-after)
                     :unknown
                     [(attr unknown :harness/status)
                      (attr unknown :harness/settled)
                      (attr unknown :harness/settlement)]
                     :malformed-error malformed-error
                     :malformed-no-write (= malformed-before malformed-after)
                     :recovered
                     [(attr recovered :harness/status)
                      (attr recovered :harness/substatus)
                      (attr recovered :harness/stop-reason)]
                     :fetched-codex
                     [(attr codex-after :harness/status)
                      (get (guidance/current-attempt codex-after) "state")]
                     :fetched-pi
                     [(attr pi-after :harness/status)
                      (get (guidance/current-attempt pi-after) "state")]}))))]
        (is (re-find #"verified preparation failure"
                     (:preparation-error result)))
        (is (= ["failed" "true" "launch-not-started" "false" []]
               (:prelaunch result)))
        (is (re-find #"no attempt record" (:positive-error result)))
        (is (true? (:positive-no-write result)))
        (is (= ["failed" "false" "no-terminal-evidence"]
               (:unknown result)))
        (is (re-find #"no attempt record" (:malformed-error result)))
        (is (true? (:malformed-no-write result)))
        (is (= ["failed" "bootstrap"
                "native guidance handoff timed out"]
               (:recovered result)))
        (is (= ["failed" "failed"] (:fetched-codex result)))
        (is (= ["running" "fetched"] (:fetched-pi result)))))))

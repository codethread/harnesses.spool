(ns ct.spools.harnesses.guidance-acknowledgement-timestamp-test
  "Authoritative timestamp regressions for guidance acknowledgement."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest acknowledgement-publishes-one-validated-transition-timestamp
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(do
                 (require '[ct.spools.harnesses.internal.lifecycle :as life])
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* accepted-runner]
                   (let [fetched!
                         (fn [suffix]
                           (let [run
                                 (harnesses/create!
                                  rt {:harness :native-codex
                                      :mode :headless
                                      :cwd "/tmp"
                                      :prompt (str "timestamp " suffix)
                                      :guidance-transport "native-v1"})
                                 started (harnesses/begin-attempt! rt (:id run))
                                 bundle
                                 (harnesses/managed-startup!
                                  rt {:harness "codex"
                                      :native-session-id (str "session-" suffix)
                                      :cwd "/tmp"
                                      :scope "root"
                                      :bootstrap
                                      (harnesses/managed-bootstrap rt (:id run))
                                      :guidance
                                      (guidance/bootstrap (:strand started))})]
                             {:id (:id run)
                              :receipt (guidance-receipt
                                        bundle "adapter-handoff")}))
                         timely (fetched! "timely")
                         timely-record
                         (guidance/current-attempt
                          (guidance/validation-run
                           rt (weaver/show rt (:id timely))))
                         timely-at
                         (str (.minusMillis
                               (java.time.Instant/parse
                                (get timely-record "deadline-at"))
                               1))
                         timely-clock-calls (atom 0)
                         timely-result
                         (with-redefs
                          [life/now (fn []
                                      (swap! timely-clock-calls inc)
                                      timely-at)]
                           (harnesses/guidance-acknowledge!
                            rt (:receipt timely)))
                         timely-after
                         (guidance/validation-run
                          rt (weaver/show rt (:id timely)))
                         expired (fetched! "expired")
                         expired-record
                         (guidance/current-attempt
                          (guidance/validation-run
                           rt (weaver/show rt (:id expired))))
                         expired-at
                         (str (.plusMillis
                               (java.time.Instant/parse
                                (get expired-record "deadline-at"))
                               1))
                         expired-result
                         (with-redefs [life/now (constantly expired-at)]
                           (harnesses/guidance-acknowledge!
                            rt (:receipt expired)))
                         expired-after
                         (guidance/validation-run
                          rt (weaver/show rt (:id expired)))
                         invalid (fetched! "invalid")
                         invalid-record
                         (guidance/current-attempt
                          (guidance/validation-run
                           rt (weaver/show rt (:id invalid))))
                         invalid-at
                         (str (.minusMillis
                               (java.time.Instant/parse
                                (get-in invalid-record
                                        ["first-fetch" "fetched-at"]))
                               1))
                         before-invalid (weaver/list rt)
                         invalid-error
                         (with-redefs [life/now (constantly invalid-at)]
                           (try
                             (harnesses/guidance-acknowledge!
                              rt (:receipt invalid))
                             nil
                             (catch clojure.lang.ExceptionInfo error
                               (ex-message error))))
                         after-invalid (weaver/list rt)]
                     {:timely-result timely-result
                      :timely-at timely-at
                      :timely-clock-calls @timely-clock-calls
                      :timely-record
                      (guidance/current-attempt timely-after)
                      :timely-attached-at
                      (spool/attr-get timely-after :harness/native-attached-at)
                      :expired-result expired-result
                      :expired-status (life/status expired-after)
                      :expired-record
                      (guidance/current-attempt expired-after)
                      :invalid-error invalid-error
                      :invalid-no-write (= before-invalid after-invalid)})))))]
        (is (= "recorded" (get-in result [:timely-result :result])))
        (is (= 1 (:timely-clock-calls result)))
        (is (= "acknowledged"
               (get-in result [:timely-record "state"])))
        (is (= (:timely-at result)
               (get-in result [:timely-record "acknowledged-at"])))
        (is (= (:timely-attached-at result)
               (get-in result [:timely-record "first-fetch" "fetched-at"])))
        (is (= "ignored" (get-in result [:expired-result :result])))
        (is (= "failed" (:expired-status result)))
        (is (= "failed" (get-in result [:expired-record "state"])))
        (is (nil? (get-in result [:expired-record "acknowledged-at"])))
        (is (re-find #"corrupt partial guidance metadata"
                     (:invalid-error result)))
        (is (true? (:invalid-no-write result)))))))

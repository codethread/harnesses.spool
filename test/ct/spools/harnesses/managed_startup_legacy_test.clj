(ns ct.spools.harnesses.managed-startup-legacy-test
  "Legacy managed startup and exact Pi continuation tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.managed-startup-test :as fixture]))

(deftest exact-binding-legacy-pi-continuation-launches-and-retries
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [root
                    (with-redefs [managed/managed-harness? (constantly false)]
                      (harnesses/create!
                       rt {:harness :pi :mode :interactive
                           :cwd "/tmp/legacy-pi-resume"
                           :session-id "legacy-pi-native"
                           :title "legacy Pi root"}))
                    root-start (harnesses/begin-attempt! rt (:id root))
                    root (harnesses/finish!
                          rt (:id root)
                          {:status :done :exit-code 0
                           :session-id "legacy-pi-native"
                           :session-usable true
                           :invocation (:invocation root-start)})
                    eligibility (harnesses/resume-eligibility rt (:id root))
                    resumed (harnesses/resume!
                             rt (:id root) {:prompt "Continue safely."})
                    identity-id (attr resumed :identity/id)
                    launcher-path (launcher/write! rt resumed ["pi"] {})
                    launcher-before (slurp launcher-path)
                    resumed-start
                    (execution/mark-interactive-running! rt (:id resumed))
                    launcher-after (slurp launcher-path)
                    bootstrap (harnesses/managed-bootstrap rt (:id resumed))
                    process-spec (#'execution/process-spec
                                  rt resumed-start
                                  {:argv ["pi"] :env {} :stdin nil})
                    failed (harnesses/finish!
                            rt (:id resumed)
                            {:status :failed :exit-code 1
                             :error "retry legacy continuation"
                             :session-id "legacy-pi-native"
                             :session-usable true
                             :invocation
                             (attr resumed-start :harness/invocation)
                             :evidence {:settled true
                                        :settlement "process-exit"}})
                    retried (harnesses/retry! rt (:id failed) {})
                    retry-launcher
                    (launcher/write! rt retried ["pi" "--session"
                                                 "legacy-pi-native"] {})
                    retry-launcher-before (slurp retry-launcher)
                    retry-started
                    (execution/mark-interactive-running! rt (:id retried))
                    retry-launcher-after (slurp retry-launcher)
                    identity-strand (identity/current rt identity-id)]
                {:eligibility eligibility
                 :root-id (:id root)
                 :run-id (:id resumed)
                 :same-identity
                 (= (attr root :identity/id) identity-id
                    (attr retried :identity/id))
                 :same-session
                 (= "legacy-pi-native"
                    (attr root :harness/session-id)
                    (attr resumed :harness/session-id)
                    (attr retried :harness/session-id))
                 :reservation (attr retried :identity/reservation-id)
                 :provisional
                 (attr retried :harness/provisional-session-id)
                 :native-attached
                 (attr retried :harness/native-attached)
                 :guidance-transport
                 (attr retried :harness/guidance-transport)
                 :guidance-template
                 (attr retried :harness/guidance-context-template)
                 :performed (targets identity-strand "performed")
                 :launcher-before launcher-before
                 :launcher-after launcher-after
                 :bootstrap bootstrap
                 :process-env (:env process-spec)
                 :retry-launcher-before retry-launcher-before
                 :retry-launcher-after retry-launcher-after
                 :retry-bootstrap
                 (harnesses/managed-bootstrap rt (:id retry-started))}))]
        (testing "exact pre-upgrade Pi binding remains natively resumable"
          (is (= {:eligible? true
                  :reason "settled with a verified usable session"}
                 (:eligibility result)))
          (is (true? (:same-identity result)))
          (is (true? (:same-session result)))
          (is (= #{(:root-id result) (:run-id result)}
                 (:performed result))))
        (testing "legacy continuation does not mint startup-v1 evidence"
          (is (nil? (:reservation result)))
          (is (nil? (:provisional result)))
          (is (nil? (:native-attached result)))
          (is (= "legacy" (:guidance-transport result)))
          (is (map? (:guidance-template result)))
          (is (nil? (:bootstrap result)))
          (is (nil? (:retry-bootstrap result)))
          (is (not (contains? (:process-env result)
                              "MILLSTRAND_MANAGED_BOOTSTRAP"))))
        (testing "interactive launch and retry retain the legacy transport"
          (doseq [source [(:launcher-before result)
                          (:launcher-after result)
                          (:retry-launcher-before result)
                          (:retry-launcher-after result)]]
            (is (not (str/includes?
                      source "MILLSTRAND_MANAGED_BOOTSTRAP")))))))))

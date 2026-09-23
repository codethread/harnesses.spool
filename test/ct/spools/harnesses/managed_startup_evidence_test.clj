(ns ct.spools.harnesses.managed-startup-evidence-test
  "Legacy evidence rejection and negative custody tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.managed-startup-test :as fixture]))

(deftest legacy-positive-evidence-and-continuation-fail-before-writes
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [legacy-create
                    (fn [session-id title]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create!
                         rt {:harness :pi :mode :interactive
                             :cwd "/tmp/legacy-pi-fences"
                             :session-id session-id
                             :title title})))
                    snapshot
                    (fn [run]
                      (let [identity-id (attr run :identity/id)]
                        [(weaver/show rt (:id run))
                         (identity/current rt identity-id)
                         (targets (identity/current rt identity-id)
                                  "performed")
                         (count (weaver/list rt))]))
                    ready (legacy-create "legacy-ready" "legacy ready")
                    ready-before (snapshot ready)
                    ready-failure
                    (failure #(harnesses/finish!
                               rt (:id ready)
                               {:status :done :exit-code 0
                                :session-id "legacy-ready"
                                :session-usable true
                                :invocation "forged-ready"}))
                    ready-unchanged (= ready-before (snapshot ready))
                    missing (legacy-create "legacy-missing" "legacy missing")
                    missing-start (harnesses/begin-attempt! rt (:id missing))
                    _ (weaver/update!
                       rt (:id missing)
                       {:attributes {:harness/invocation nil}})
                    missing-before (snapshot missing)
                    missing-failure
                    (failure #(harnesses/finish!
                               rt (:id missing)
                               {:status :done :exit-code 0
                                :session-id "legacy-missing"
                                :session-usable true
                                :invocation (:invocation missing-start)}))
                    missing-unchanged (= missing-before (snapshot missing))
                    token (legacy-create "legacy-token" "legacy token")
                    token-start (harnesses/begin-attempt! rt (:id token))
                    token-before (snapshot token)
                    token-failure
                    (failure #(harnesses/finish!
                               rt (:id token)
                               {:status :done :exit-code 0
                                :session-id "legacy-token"
                                :session-usable true}))
                    token-unchanged (= token-before (snapshot token))
                    late (legacy-create "legacy-late-fence"
                                        "legacy late fence")
                    late (harnesses/finish!
                          rt (:id late)
                          {:status :failed :error "prelaunch"})
                    late-before (snapshot late)
                    late-failure
                    (failure #(harnesses/settle-outcome!
                               rt (:id late)
                               {:status :done :exit-code 0
                                :session-id "legacy-late-fence"
                                :session-usable true
                                :invocation "forged-late"}
                               {:settled true :settlement "process-exit"}))
                    late-unchanged (= late-before (snapshot late))
                    late-session
                    (legacy-create "legacy-late-session"
                                   "legacy late session")
                    late-session-start
                    (harnesses/begin-attempt! rt (:id late-session))
                    late-session
                    (harnesses/finish!
                     rt (:id late-session)
                     {:status :failed :exit-code 1
                      :error "awaiting custody"
                      :invocation (:invocation late-session-start)
                      :evidence {:settled false
                                 :settlement "no-terminal-evidence"}})
                    late-session-before (snapshot late-session)
                    late-session-failure
                    (failure #(harnesses/settle-outcome!
                               rt (:id late-session)
                               {:status :done :exit-code 0
                                :session-id "wrong-late-session"
                                :session-usable true
                                :invocation (:invocation late-session-start)}
                               {:settled true :settlement "process-exit"}))
                    late-session-unchanged
                    (= late-session-before (snapshot late-session))
                    valid (legacy-create "legacy-valid" "legacy valid")
                    valid-start (harnesses/begin-attempt! rt (:id valid))
                    valid (harnesses/finish!
                           rt (:id valid)
                           {:status :done :exit-code 0
                            :session-id "legacy-valid"
                            :session-usable true
                            :invocation (:invocation valid-start)})
                    _ (weaver/update!
                       rt (:id valid)
                       {:attributes {:harness/session-id "mismatched-run"}})
                    mismatch-before (snapshot valid)
                    mismatch-failure
                    (failure #(harnesses/resume! rt (:id valid) {}))
                    mismatch-unchanged (= mismatch-before (snapshot valid))
                    unproven
                    (weaver/add!
                     rt {:title "unproven legacy Pi"
                         :attributes
                         (-> (:attributes valid)
                             (assoc :harness/session-id "legacy-valid")
                             (dissoc :harness/continued))})
                    unproven
                    (weaver/update!
                     rt (:id unproven)
                     {:attributes
                      {:harness/guidance-bundle-sha256
                       ((requiring-resolve
                         'ct.spools.harnesses.internal.strict-json/canonical-sha256)
                        [(:id unproven)
                         (.getCanonicalPath
                          (java.io.File. (get-in rt [:metadata :config-dir])))
                         (attr unproven :harness/guidance-context)])}})
                    unproven-before
                    [(weaver/show rt (:id unproven))
                     (identity/current rt (attr unproven :identity/id))
                     (targets (identity/current rt
                                                (attr unproven :identity/id))
                              "performed")
                     (count (weaver/list rt))]
                    unproven-failure
                    (failure #(harnesses/resume! rt (:id unproven) {}))
                    unproven-after
                    [(weaver/show rt (:id unproven))
                     (identity/current rt (attr unproven :identity/id))
                     (targets (identity/current rt
                                                (attr unproven :identity/id))
                              "performed")
                     (count (weaver/list rt))]
                    prelaunch
                    (legacy-create "legacy-prelaunch" "legacy prelaunch")
                    prelaunch (harnesses/finish!
                               rt (:id prelaunch)
                               {:status :failed
                                :error "launcher preparation failed"})]
                {:ready [ready-failure ready-unchanged]
                 :missing [missing-failure missing-unchanged]
                 :token [token-failure token-unchanged]
                 :late [late-failure late-unchanged]
                 :late-session [late-session-failure
                                late-session-unchanged]
                 :mismatch [mismatch-failure mismatch-unchanged]
                 :unproven [unproven-failure
                            (= unproven-before unproven-after)]
                 :prelaunch-status (attr prelaunch :harness/status)
                 :prelaunch-error (attr prelaunch :harness/error)}))]
        (testing "positive legacy evidence requires a durable exact fence"
          (doseq [[failure unchanged?]
                  (map result [:ready :missing :token :late])]
            (is (re-find #"(durable launch fence|invocation)"
                         (:message failure)))
            (is (true? unchanged?))))
        (testing "malformed Pi session, binding, and provenance write nothing"
          (is (re-find #"does not match"
                       (get-in result [:late-session 0 :message])))
          (is (true? (get-in result [:late-session 1])))
          (is (re-find #"does not match" (get-in result [:mismatch 0 :message])))
          (is (true? (get-in result [:mismatch 1])))
          (is (re-find #"did not perform"
                       (get-in result [:unproven 0 :message])))
          (is (true? (get-in result [:unproven 1]))))
        (testing "a genuine prelaunch failure remains recordable"
          (is (= "failed" (:prelaunch-status result)))
          (is (= "launcher preparation failed"
                 (:prelaunch-error result))))))))

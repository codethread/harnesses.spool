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

(deftest legacy-negative-custody-survives-invalid-identity-binding
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [legacy-create
                    (fn [harness session-id title]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create!
                         rt {:harness harness :mode :interactive
                             :cwd "/tmp/legacy-negative-custody"
                             :session-id session-id
                             :title title})))
                    fail-unsettled
                    (fn [run]
                      (let [started (harnesses/begin-attempt! rt (:id run))]
                        {:invocation (:invocation started)
                         :run
                         (harnesses/finish!
                          rt (:id run)
                          {:status :failed :exit-code 1
                           :error "primary provider failure"
                           :invocation (:invocation started)
                           :evidence {:settled false
                                      :settlement
                                      "no-terminal-evidence"}})}))
                    cancellation
                    (fn [session-id invocation]
                      {:status :failed :exit-code 143
                       :error "cancelled"
                       :session-id session-id
                       :session-usable false
                       :invocation invocation})
                    evidence {:settled true
                              :settlement "graceful-cancellation"
                              :cancelled? true}
                    codex-start
                    (fail-unsettled
                     (legacy-create :codex "legacy-codex-custody"
                                    "legacy Codex custody"))
                    codex-run (:run codex-start)
                    codex-invocation (:invocation codex-start)
                    _ (weaver/update!
                       rt (:id codex-run)
                       {:attributes {:identity/id
                                     "missing-legacy-identity"}})
                    codex-run (weaver/show rt (:id codex-run))
                    codex-outcome
                    (cancellation "legacy-codex-custody"
                                  codex-invocation)
                    codex-attachment-failure
                    (failure #(managed/validate-legacy-outcome!
                               rt codex-run
                               (assoc codex-outcome
                                      :session-usable true)))
                    codex-before [(weaver/show rt (:id codex-run))
                                  (count (weaver/list rt))]
                    codex-stale
                    (failure #(harnesses/settle-outcome!
                               rt (:id codex-run)
                               (assoc codex-outcome :invocation "stale")
                               evidence))
                    codex-after-stale
                    [(weaver/show rt (:id codex-run))
                     (count (weaver/list rt))]
                    codex-settled
                    (harnesses/settle-outcome!
                     rt (:id codex-run) codex-outcome evidence)
                    pi-start
                    (fail-unsettled
                     (legacy-create :pi "legacy-pi-custody"
                                    "legacy Pi custody"))
                    pi-run (:run pi-start)
                    pi-invocation (:invocation pi-start)
                    pi-identity-id (attr pi-run :identity/id)
                    pi-identity (identity/current rt pi-identity-id)
                    _ (weaver/update!
                       rt (:id pi-identity)
                       {:attributes {:identity/harness "codex"}})
                    pi-run (weaver/show rt (:id pi-run))
                    pi-outcome
                    (cancellation "legacy-pi-custody" pi-invocation)
                    pi-attachment-failure
                    (failure #(managed/validate-legacy-outcome!
                               rt pi-run
                               (assoc pi-outcome :session-usable true)))
                    pi-settled
                    (harnesses/settle-outcome!
                     rt (:id pi-run) pi-outcome evidence)
                    projection
                    (fn [run]
                      {:status (attr run :harness/status)
                       :error (attr run :harness/error)
                       :exit-code (attr run :harness/exit-code)
                       :session-id (attr run :harness/session-id)
                       :session-usable
                       (attr run :harness/session-usable)
                       :settled (attr run :harness/settled)
                       :settlement (attr run :harness/settlement)
                       :reservation
                       (attr run :identity/reservation-id)
                       :native-attached
                       (attr run :harness/native-attached)})]
                {:codex-attachment-failure codex-attachment-failure
                 :codex-stale codex-stale
                 :codex-stale-no-write
                 (= codex-before codex-after-stale)
                 :codex (projection codex-settled)
                 :pi-attachment-failure pi-attachment-failure
                 :pi (projection pi-settled)
                 :pi-identity-harness
                 (attr (identity/current rt pi-identity-id)
                       :identity/harness)}))]
        (testing "broken legacy identities still fail positive validation"
          (is (re-find #"does not resolve uniquely"
                       (get-in result
                               [:codex-attachment-failure :message])))
          (is (re-find #"belongs to another harness"
                       (get-in result [:pi-attachment-failure :message]))))
        (testing "negative custody remains exactly invocation-fenced"
          (is (re-find #"missing or stale invocation"
                       (get-in result [:codex-stale :message])))
          (is (true? (:codex-stale-no-write result))))
        (testing "failed unusable cancellation settles without attachment"
          (doseq [[provider session-id]
                  [[:codex "legacy-codex-custody"]
                   [:pi "legacy-pi-custody"]]]
            (is (= {:status "failed"
                    :error "primary provider failure"
                    :exit-code 143
                    :session-id session-id
                    :session-usable "false"
                    :settled "true"
                    :settlement "graceful-cancellation"
                    :reservation nil
                    :native-attached nil}
                   (provider result))))
          (is (= "codex" (:pi-identity-harness result))))))))

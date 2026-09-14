(ns ct.spools.harnesses.managed-startup-legacy-test
  "Legacy managed startup and exact Pi continuation tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.managed-startup-test :as fixture]))

(deftest pre-reservation-runs-finish-under-the-new-backend
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [legacy-create
                    (fn [request]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create! rt request)))
                    snapshot
                    (fn [run]
                      (let [identity-strand
                            (identity/current rt (attr run :identity/id))]
                        {:run (weaver/show rt (:id run))
                         :identity identity-strand
                         :performed (targets identity-strand "performed")
                         :strand-count (count (weaver/list rt))}))
                    completed-run
                    (legacy-create
                     {:harness :codex :mode :interactive
                      :cwd "/tmp/legacy-complete"
                      :title "serialized legacy completion"})
                    completed-provisional
                    (attr completed-run :harness/session-id)
                    completed-start
                    (harnesses/begin-attempt! rt (:id completed-run))
                    identity-before
                    (identity/current rt (attr completed-run :identity/id))
                    stale-before (snapshot completed-run)
                    stale
                    (harnesses/finish!
                     rt (:id completed-run)
                     {:status :done :exit-code 0 :result "stale"
                      :session-id "legacy-thread"
                      :session-usable true
                      :invocation "originating-stale-invocation"})
                    stale-after (snapshot completed-run)
                    completed
                    (harnesses/finish!
                     rt (:id completed-run)
                     {:status :done :exit-code 0 :result "complete"
                      :session-id "legacy-thread"
                      :session-usable true
                      :invocation (:invocation completed-start)})
                    terminal-before (snapshot completed)
                    terminal-replay
                    (harnesses/finish!
                     rt (:id completed)
                     {:status :done :exit-code 0
                      :session-usable true
                      :invocation "terminal-replay"})
                    terminal-after (snapshot completed)
                    before-resume-count (count (weaver/list rt))
                    legacy-resume
                    (failure #(harnesses/resume! rt (:id completed) {}))
                    after-resume-count (count (weaver/list rt))
                    fresh
                    (harnesses/create!
                     rt {:harness :codex :mode :interactive
                         :cwd "/tmp/legacy-complete"
                         :title "fresh after legacy"
                         :after (:id completed)})
                    cancelled-run
                    (legacy-create
                     {:harness :pi :mode :interactive
                      :cwd "/tmp/legacy-cancel"
                      :session-id "legacy-pi-session"
                      :title "serialized legacy cancellation"})
                    cancelled-start
                    (harnesses/begin-attempt! rt (:id cancelled-run))
                    _ (harnesses/stop! rt (:id cancelled-run)
                                       {:reason "replacement cancellation"})
                    cancelled
                    (harnesses/finish!
                     rt (:id cancelled-run)
                     {:status :failed :exit-code 143
                      :error "cancelled"
                      :session-id "legacy-pi-session"
                      :session-usable true
                      :invocation (:invocation cancelled-start)
                      :evidence {:settled true
                                 :settlement "graceful-cancellation"
                                 :cancelled? true}})
                    late-run
                    (legacy-create
                     {:harness :codex :mode :interactive
                      :cwd "/tmp/legacy-late"
                      :title "serialized legacy late custody"})
                    late-start
                    (harnesses/begin-attempt! rt (:id late-run))
                    _ (harnesses/stop! rt (:id late-run)
                                       {:reason "late custody"})
                    late-failed
                    (harnesses/finish!
                     rt (:id late-run)
                     {:status :failed :exit-code 1
                      :error "primary failure"
                      :invocation (:invocation late-start)
                      :evidence {:settled false
                                 :settlement "no-terminal-evidence"}})
                    late-settled
                    (harnesses/settle-outcome!
                     rt (:id late-failed)
                     {:status :failed :exit-code 143
                      :error "cancelled"
                      :session-id "legacy-late-thread"
                      :session-usable true
                      :invocation (:invocation late-start)}
                     {:settled true
                      :settlement "graceful-cancellation"
                      :cancelled? true})
                    malformed-run
                    (harnesses/create!
                     rt {:harness :codex :mode :interactive
                         :cwd "/tmp/malformed-current"
                         :title "malformed current reservation"})
                    malformed-start
                    (harnesses/begin-attempt! rt (:id malformed-run))
                    malformed-identity
                    (attr malformed-run :identity/id)
                    _ (weaver/update!
                       rt (:id malformed-run)
                       {:attributes {:identity/reservation-id nil}})
                    malformed-before
                    [(weaver/show rt (:id malformed-run))
                     (identity/current rt malformed-identity)
                     (targets (identity/current rt malformed-identity)
                              "performed")]
                    malformed-failure
                    (failure
                     #(harnesses/finish!
                       rt (:id malformed-run)
                       {:status :done :exit-code 0 :result "must reject"
                        :session-id "malformed-thread"
                        :session-usable true
                        :invocation (:invocation malformed-start)}))
                    malformed-after
                    [(weaver/show rt (:id malformed-run))
                     (identity/current rt malformed-identity)
                     (targets (identity/current rt malformed-identity)
                              "performed")]]
                {:completed-status (attr completed :harness/status)
                 :completed-substatus (attr completed :harness/substatus)
                 :completed-session (attr completed :harness/session-id)
                 :completed-usable (attr completed :harness/session-usable)
                 :completed-reservation
                 (attr completed :identity/reservation-id)
                 :completed-native-attached
                 (attr completed :harness/native-attached)
                 :completed-provisional-attribute
                 (attr completed :harness/provisional-session-id)
                 :identity-native-before
                 (attr identity-before :identity/native-session-id)
                 :identity-native-after
                 (attr (identity/current rt (attr completed :identity/id))
                       :identity/native-session-id)
                 :stale-returned-unchanged
                 (= (:run stale-before) stale)
                 :stale-no-write (= stale-before stale-after)
                 :terminal-returned-unchanged
                 (= (:run terminal-before) terminal-replay)
                 :terminal-no-write (= terminal-before terminal-after)
                 :completed-provisional completed-provisional
                 :legacy-resume legacy-resume
                 :resume-no-write (= before-resume-count after-resume-count)
                 :fresh-identity-different
                 (not= (attr completed :identity/id)
                       (attr fresh :identity/id))
                 :fresh-reservation (attr fresh :identity/reservation-id)
                 :cancelled-status (attr cancelled :harness/status)
                 :cancelled-substatus (attr cancelled :harness/substatus)
                 :cancelled-settled (attr cancelled :harness/settled)
                 :cancelled-settlement
                 (attr cancelled :harness/settlement)
                 :cancelled-reservation
                 (attr cancelled :identity/reservation-id)
                 :late-status (attr late-settled :harness/status)
                 :late-error (attr late-settled :harness/error)
                 :late-settled (attr late-settled :harness/settled)
                 :late-settlement
                 (attr late-settled :harness/settlement)
                 :late-session (attr late-settled :harness/session-id)
                 :late-usable (attr late-settled :harness/session-usable)
                 :malformed-failure malformed-failure
                 :malformed-unchanged
                 (= malformed-before malformed-after)}))]
        (testing "an old serialized binding completes without invented evidence"
          (is (= "stopped" (:completed-status result)))
          (is (= "completed" (:completed-substatus result)))
          (is (= "legacy-thread" (:completed-session result)))
          (is (= "true" (:completed-usable result)))
          (is (nil? (:completed-reservation result)))
          (is (nil? (:completed-native-attached result)))
          (is (nil? (:completed-provisional-attribute result)))
          (is (= (:completed-provisional result)
                 (:identity-native-before result)
                 (:identity-native-after result))))
        (testing "stale and terminal callbacks are full no-ops"
          (is (true? (:stale-returned-unchanged result)))
          (is (true? (:stale-no-write result)))
          (is (true? (:terminal-returned-unchanged result)))
          (is (true? (:terminal-no-write result))))
        (testing "legacy continuity is explicit while fresh work remains native-v1"
          (is (re-find #"legacy Codex run has no verified native binding"
                       (get-in result [:legacy-resume :data :reason])))
          (is (true? (:resume-no-write result)))
          (is (true? (:fresh-identity-different result)))
          (is (string? (:fresh-reservation result))))
        (testing "a replacement backend records cancellation and late custody"
          (is (= "stopped" (:cancelled-status result)))
          (is (= "requested" (:cancelled-substatus result)))
          (is (= "true" (:cancelled-settled result)))
          (is (= "graceful-cancellation"
                 (:cancelled-settlement result)))
          (is (nil? (:cancelled-reservation result)))
          (is (= "failed" (:late-status result)))
          (is (= "primary failure" (:late-error result)))
          (is (= "true" (:late-settled result)))
          (is (= "graceful-cancellation" (:late-settlement result)))
          (is (= "legacy-late-thread" (:late-session result)))
          (is (= "true" (:late-usable result))))
        (testing "a damaged reservation-backed run is not treated as legacy"
          (is (re-find #"no identity reservation"
                       (get-in result [:malformed-failure :message])))
          (is (true? (:malformed-unchanged result))))))))

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

(deftest stale-and-terminal-managed-finishes-are-complete-no-ops
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [snapshot
                    (fn [run]
                      (let [current (weaver/show rt (:id run))
                            identity-strand
                            (identity/current rt (attr current :identity/id))]
                        {:run current
                         :identity identity-strand
                         :performed (targets identity-strand "performed")
                         :strand-count (count (weaver/list rt))}))
                    probe
                    (fn [run outcome]
                      (let [before (snapshot run)
                            returned (harnesses/finish! rt (:id run) outcome)
                            after (snapshot run)]
                        {:returned-unchanged (= (:run before) returned)
                         :no-write (= before after)}))]
                (into {}
                      (for [provider [:codex :pi]]
                        (let [run (harnesses/create!
                                   rt {:harness provider :mode :interactive
                                       :cwd "/tmp/managed-finish-no-op"
                                       :title (str (name provider) " no-op")})
                              started (harnesses/begin-attempt! rt (:id run))
                              stale
                              (into {}
                                    (for [usable? [true false]]
                                      [usable?
                                       (probe
                                        run
                                        {:status :done :exit-code 0
                                         :session-id "unrelated-stale-session"
                                         :session-usable usable?
                                         :invocation "stale-invocation"})]))
                              terminal
                              (harnesses/finish!
                               rt (:id run)
                               {:status :failed :exit-code 1
                                :error "terminal baseline"
                                :invocation (:invocation started)
                                :evidence {:settled true
                                           :settlement "process-exit"}})
                              terminal
                              (into {}
                                    (for [usable? [true false]]
                                      [usable?
                                       (probe
                                        terminal
                                        {:status :done :exit-code 0
                                         :session-id "unrelated-terminal-session"
                                         :session-usable usable?
                                         :invocation "terminal-replay"})]))]
                          [provider {:stale stale :terminal terminal}])))))]
        (doseq [provider [:codex :pi]
                stage [:stale :terminal]
                usable? [true false]]
          (testing (str (name provider) " " (name stage)
                        " callback with usable=" usable?)
            (is (true? (get-in result
                               [provider stage usable? :returned-unchanged])))
            (is (true? (get-in result
                               [provider stage usable? :no-write])))))))))

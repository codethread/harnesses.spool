(ns ct.spools.harnesses.lifecycle-custody-test
  "Custody reconciliation and integrated lifecycle transition tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.lifecycle-test :as fixture]
            [millstrand.test.alpha :as test-alpha]))

(deftest custody-inspection-accepts-terminal-runs-without-an-invocation
  (fixture/with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.execution :as execution]
                         '[ct.spools.harnesses.internal.process-custody :as custody]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:headless}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      run (harnesses/create!
                           rt {:harness :fake :mode :headless :cwd "/tmp"
                               :prompt "Do not launch this failed run."
                               :title "Unsettled terminal run"})
                      id (:id run)
                      _ (harnesses/finish!
                         rt id {:status :failed :error "prior failure"})
                      _ (weaver/update!
                         rt id
                         {:attributes {:harness/attempt 1
                                       :harness/process-owner "agent-harness/run"
                                       :harness/process-key (str id "/attempt-1")
                                       :harness/process-handle "missing"}})]
                  (with-redefs [custody/list-owned (constantly [])]
                    (let [opened (execution/open-execution! {:runtime rt})]
                      (try
                        (execution/inspect-owned! rt)
                        (let [retained (weaver/show rt id)]
                          {:deferred? (contains? opened :deferred-recovery)
                           :status (spool/attr-get retained :harness/status)
                           :settled (spool/attr-get retained :harness/settled)
                           :invocation (spool/attr-get retained :harness/invocation)
                           :error (spool/attr-get retained :harness/error)})
                        (finally
                          (execution/close-execution! {:runtime rt}))))))))]
        (is (= {:deferred? false :status "failed" :settled "false"
                :invocation nil :error "prior failure"}
               result))))))

(deftest custody-inspection-coalesces-and-deduplicates-failures
  (fixture/with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.execution :as execution]
                         '[ct.spools.harnesses.internal.process-custody :as custody]
                         '[ct.spools.harnesses.internal.runs :as runs]
                         '[millstrand.api.current.alpha :as current])
                (let [rt (current/runtime)
                      running (mapv (fn [id]
                                      {:id id
                                       :attributes
                                       {:harness/status "running"
                                        :harness/mode "headless"
                                        :harness/settled "false"
                                        :harness/attempt 1
                                        :harness/process-owner "agent-harness/run"
                                        :harness/process-key (str id "/attempt-1")
                                        :harness/process-handle (str "handle-" id)}})
                                    ["one" "two"])
                      records (mapv (fn [run]
                                      {:owner custody/owner
                                       :key (get-in run [:attributes
                                                         :harness/process-key])
                                       :handle (get-in run [:attributes
                                                            :harness/process-handle])
                                       :phase :running})
                                    running)
                      terminal (assoc-in (first running)
                                         [:attributes :harness/status]
                                         "failed")
                      finish-count (atom 0)
                      schedule-count (atom 0)
                      _ ((ns-resolve 'ct.spools.harnesses.execution
                                     'activate-state!) rt)]
                  (try
                    (with-redefs-fn
                      {#'runs/inspectable-headless (fn [_ _] running)
                       #'weaver/show
                       (fn [_ id] (some #(when (= id (:id %)) %) running))
                       #'custody/list-owned (constantly records)
                       (ns-resolve 'ct.spools.harnesses.execution
                                   'schedule-inspection!)
                       (fn [& _] (swap! schedule-count inc))}
                      #(execution/inspect-owned! rt))
                    (with-redefs [runs/inspectable-headless
                                  (fn [_ _] [terminal])
                                  weaver/show (fn [_ _] terminal)
                                  custody/list-owned (constantly [])
                                  harnesses/finish!
                                  (fn [_ _ _] (swap! finish-count inc))]
                      (execution/inspect-owned! rt)
                      (execution/inspect-owned! rt))
                    {:scheduled @schedule-count
                     :finish-count @finish-count
                     :state-version
                     @(ns-resolve 'ct.spools.harnesses.execution
                                  'state-version)
                     :state-keys
                     (set (keys ((ns-resolve 'ct.spools.harnesses.execution
                                             'state) rt)))}
                    (finally
                      ((:close-fn
                        ((ns-resolve 'ct.spools.harnesses.execution
                                     'deactivate-state!) rt))))))))]
        (testing "many live records schedule one runtime-wide recurring pass"
          (is (= 1 (:scheduled result))))
        (testing "an identical missing-custody failure is persisted once"
          (is (= 1 (:finish-count result))))
        (testing "runtime state version owns reconciliation coordination"
          (is (= 4 (:state-version result)))
          (is (every? (:state-keys result)
                      [:inspection-scheduled? :reconciliation-failures])))))))

(deftest late-successful-settlement-preserves-primary-failure
  (fixture/with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool])
                (let [rt (current/runtime)
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:interactive}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      run (harnesses/create!
                           rt {:harness :fake
                               :mode :interactive
                               :title "late successful settlement"})
                      failed (harnesses/finish!
                              rt (:id run)
                              {:status :failed
                               :error "primary launch failure"})
                      settled (harnesses/settle-outcome!
                               rt (:id failed)
                               {:status :done
                                :exit-code 0
                                :result "released"
                                :session-usable true}
                               {:settled true
                                :settlement "process-exit"})]
                  {:status (spool/attr-get settled :harness/status)
                   :error (spool/attr-get settled :harness/error)
                   :exit-code (spool/attr-get settled :harness/exit-code)
                   :result (spool/attr-get settled :harness/result)
                   :settled (spool/attr-get settled :harness/settled)
                   :settlement (spool/attr-get settled
                                               :harness/settlement)})))]
        (is (= {:status "failed"
                :error "primary launch failure"
                :exit-code 0
                :result "released"
                :settled "true"
                :settlement "process-exit"}
               result))))))

(deftest lifecycle-contract-in-disposable-world
  (fixture/with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.internal.lifecycle :as life]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      attr spool/attr-get
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:headless :interactive}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      cancelled (harnesses/create!
                                 rt {:harness :fake
                                     :mode :interactive
                                     :title "cancel-before-launch"})
                      cancelled (harnesses/stop! rt (:id cancelled)
                                                 {:reason "never start"})
                      start-after-stop
                      (try (harnesses/begin-attempt! rt (:id cancelled))
                           :started
                           (catch clojure.lang.ExceptionInfo _
                             :refused))
                      racing (harnesses/create!
                              rt {:harness :fake :mode :interactive
                                  :title "race"})
                      started (harnesses/begin-attempt! rt (:id racing))
                      racing (harnesses/stop! rt (:id racing)
                                              {:reason "stop now"})
                      racing-after-stop {:status (life/status racing)
                                         :substatus (life/substatus racing)
                                         :settled (life/settled? racing)}
                      missing-invocation
                      (try
                        (harnesses/finish! rt (:id racing)
                                           {:status :done :exit-code 0})
                        :accepted
                        (catch clojure.lang.ExceptionInfo e
                          (ex-message e)))
                      racing (harnesses/finish!
                              rt (:id racing)
                              {:status :done :exit-code 0
                               :invocation (:invocation started)
                               :session-usable true})
                      stale (harnesses/create!
                             rt {:harness :fake :mode :interactive
                                 :title "stale"})
                      stale-start (harnesses/begin-attempt! rt (:id stale))
                      stale-before-wrong (weaver/show rt (:id stale))
                      stale-after-wrong
                      (harnesses/finish!
                       rt (:id stale)
                       {:status :done :exit-code 0
                        :invocation "not-this-attempt"
                        :session-usable true})
                      stale-after-right
                      (harnesses/finish!
                       rt (:id stale)
                       {:status :done :exit-code 0
                        :invocation (:invocation stale-start)
                        :session-usable true})
                      failed (harnesses/create!
                              rt {:harness :fake :mode :interactive
                                  :session-id "failed-session"
                                  :title "failed"})
                      failed (harnesses/finish!
                              rt (:id failed)
                              {:status :failed :error "boom"})
                      failed-resume
                      (try (harnesses/resume! rt (:id failed) {})
                           :resumed
                           (catch clojure.lang.ExceptionInfo e
                             (ex-message e)))
                      conflict (try
                                 (harnesses/create!
                                  rt {:harness :fake :mode :interactive
                                      :session-id "failed-session"
                                      :title "conflict"})
                                 :created
                                 (catch clojure.lang.ExceptionInfo e
                                   (ex-message e)))
                      first-req (harnesses/create!
                                 rt {:harness :fake :mode :interactive
                                     :title "req"
                                     :request-id "req-1"})
                      same-req (harnesses/create!
                                rt {:harness :fake :mode :interactive
                                    :title "req"
                                    :request-id "req-1"})
                      conflict-req
                      (try (harnesses/create!
                            rt {:harness :fake :mode :interactive
                                :title "other"
                                :request-id "req-1"})
                           :created
                           (catch clojure.lang.ExceptionInfo e
                             {:message (ex-message e)
                              :run (get (ex-data e) :run)}))
                      target (weaver/add! rt {:title "served"})
                      head (harnesses/create!
                            rt {:harness :fake :mode :interactive
                                :title "head"
                                :target (:id target)
                                :context {:policy "frozen"}
                                :session-id "lineage"})
                      head (harnesses/finish!
                            rt (:id head)
                            {:status :done :exit-code 0
                             :session-usable true})
                      child (harnesses/resume!
                             rt (:id head)
                             {:request-id "resume-1"})
                      changed-cwd
                      (try (harnesses/resume! rt (:id head)
                                              {:cwd "/other"})
                           :resumed
                           (catch clojure.lang.ExceptionInfo e
                             (ex-message e)))
                      child-start (harnesses/begin-attempt! rt (:id child))
                      repeated (harnesses/resume!
                                rt (:id head)
                                {:request-id "resume-1"})
                      stale-head
                      (try (harnesses/resolve-resume-run
                            rt {:session-id "lineage"})
                           :resolved
                           (catch clojure.lang.ExceptionInfo e
                             (ex-message e)))
                      assigned-retry
                      (let [bound (harnesses/create!
                                   rt {:harness :fake :mode :interactive
                                       :request-id "assigned-1"
                                       :title "assigned"})
                            bound (harnesses/finish!
                                   rt (:id bound)
                                   {:status :failed
                                    :exit-code 1
                                    :error "no"})]
                        (try (harnesses/retry! rt (:id bound) {})
                             :retried
                             (catch clojure.lang.ExceptionInfo e
                               (ex-message e))))
                      missing (weaver/list-query
                               rt 'agent-run-terminal {:run-id "no-such-run"})
                      active (weaver/list-query
                              rt 'agent-run-active {:run-id (:id child)})
                      terminal (weaver/list-query
                                rt 'agent-run-terminal {:run-id (:id racing)})
                      settled (weaver/list-query
                               rt 'agent-run-settled {:run-id (:id racing)})
                      failed-terminal (weaver/list-query
                                       rt 'agent-run-terminal
                                       {:run-id (:id failed)})
                      failed-settled (weaver/list-query
                                      rt 'agent-run-settled
                                      {:run-id (:id failed)})]
                  {:stop-before-launch
                   {:status (life/status cancelled)
                    :substatus (life/substatus cancelled)
                    :settled (life/settled? cancelled)
                    :start start-after-stop}
                   :stop-vs-finish
                   {:after-stop racing-after-stop
                    :missing-invocation missing-invocation
                    :after-done {:status (life/status racing)
                                 :substatus (life/substatus racing)
                                 :settled (life/settled? racing)}}
                   :stale {:wrong-unchanged
                           (= stale-before-wrong stale-after-wrong)
                           :right-status (life/status stale-after-right)}
                   :failed-resume failed-resume
                   :session-conflict conflict
                   :duplicate {:same? (= (:id first-req) (:id same-req))
                               :conflict-run (:run conflict-req)
                               :first (:id first-req)}
                   :inherit {:target (attr child :harness/target)
                             :context (attr child :harness/context)
                             :logical (attr child :harness/logical-id)
                             :head-logical (life/logical-id head)}
                   :dedup-before-eligibility (= (:id child) (:id repeated))
                   :changed-cwd changed-cwd
                   :stale-head stale-head
                   :assigned-retry assigned-retry
                   :queries {:missing (mapv :id missing)
                             :active (mapv :id active)
                             :terminal (mapv :id terminal)
                             :settled (mapv :id settled)
                             :failed-terminal (mapv :id failed-terminal)
                             :failed-settled (mapv :id failed-settled)}
                   :child-running (life/status (weaver/show rt (:id child)))
                   :await-op (try (weaver/resolve-op rt 'agent)
                                  (get-in (weaver/resolve-op rt 'agent)
                                          [:arg-spec :subcommands])
                                  (catch Throwable _ {}))
                   :child-invocation (:invocation child-start)})))]
        (testing "stop before launch settles and cannot start"
          (is (= "stopped" (get-in result [:stop-before-launch :status])))
          (is (= "requested" (get-in result [:stop-before-launch :substatus])))
          (is (true? (get-in result [:stop-before-launch :settled])))
          (is (= :refused (get-in result [:stop-before-launch :start]))))
        (testing "stop stays running until a normal finish settles completed"
          (is (= {:status "running" :substatus "requested" :settled false}
                 (get-in result [:stop-vs-finish :after-stop])))
          (is (re-find #"requires its invocation token"
                       (get-in result [:stop-vs-finish :missing-invocation])))
          (is (= {:status "stopped" :substatus "completed" :settled true}
                 (get-in result [:stop-vs-finish :after-done]))))
        (testing "stale callbacks cannot finish a newer attempt"
          (is (true? (get-in result [:stale :wrong-unchanged])))
          (is (= "stopped" (get-in result [:stale :right-status]))))
        (testing "native resume preserves settings"
          (is (re-find #"cannot change cwd" (:changed-cwd result))))
        (testing "failed sessions are not natively resumable without usable evidence"
          (is (string? (:failed-resume result)))
          (is (re-find #"cannot be resumed" (:failed-resume result))))
        (testing "failed-unsettled rows keep the session reservation"
          (is (re-find #"active managed writer" (:session-conflict result))))
        (testing "duplicate requests converge and conflicts name the holder"
          (is (true? (get-in result [:duplicate :same?])))
          (is (= (get-in result [:duplicate :first])
                 (get-in result [:duplicate :conflict-run]))))
        (testing "resume inherits target/context and logical identity"
          (is (some? (get-in result [:inherit :target])))
          (is (= {:policy "frozen"} (get-in result [:inherit :context])))
          (is (= (get-in result [:inherit :head-logical])
                 (get-in result [:inherit :logical]))))
        (testing "request dedup wins before resume eligibility"
          (is (true? (:dedup-before-eligibility result)))
          (is (= "running" (:child-running result))))
        (testing "accepted running children block stale ancestor heads"
          (is (re-find #"No settled harness run head"
                       (:stale-head result))))
        (testing "request-bound assigned work cannot be retried in place"
          (is (re-find #"request-bound" (:assigned-retry result))))
        (testing "named queries return the target run only on verified evidence"
          (is (= [] (get-in result [:queries :missing])))
          (is (= 1 (count (get-in result [:queries :active]))))
          (is (= 1 (count (get-in result [:queries :terminal]))))
          (is (= 1 (count (get-in result [:queries :settled]))))
          (is (= 1 (count (get-in result [:queries :failed-terminal]))))
          (is (= [] (get-in result [:queries :failed-settled]))))
        (testing "agent await is not an agent subcommand"
          (is (not (contains? (:await-op result) "await"))))))))

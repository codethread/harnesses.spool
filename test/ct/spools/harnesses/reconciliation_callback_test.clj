(ns ct.spools.harnesses.reconciliation-callback-test
  "Interactive callback fencing and retry custody regression tests."
  (:require [clojure.test :refer [deftest is testing]]
            [millstrand.test.alpha :as test-alpha]))

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    {:deps
     {'ct.spools/harnesses
      {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity
      {:local/root (.getCanonicalPath identity-root)}}}))

(defn- full-world-options []
  {:storage :sqlite-memory
   :deps-edn (pr-str (world-deps))
   :init-clj
   "(require '[millstrand.api.current.alpha :as current]
             '[millstrand.api.runtime.alpha :as runtime])
    (def rt (current/runtime))
    (runtime/module! rt :identity
      {:ns 'millhouse.spools.identity
       :required? true})
    (runtime/module! rt :harnesses
      {:ns 'ct.spools.harnesses.spool
       :after [:identity]
       :required? true})"})

(deftest retry-retires-prior-provider-custody-and-keeps-fences
  (test-alpha/run-with-weaver-world
   (full-world-options)
   (fn [ctx]
     (let [result
           (test-alpha/repl!
            ctx
            '(do
               (require '[ct.spools.harnesses :as harnesses]
                        '[ct.spools.harnesses.execution :as execution]
                        '[ct.spools.harnesses.reconciliation :as reconcile]
                        '[millstrand.api.current.alpha :as current]
                        '[millstrand.api.spool.alpha :as spool]
                        '[millstrand.api.weaver.alpha :as weaver])
               (let [rt (current/runtime)
                     pid (.pid (java.lang.ProcessHandle/current))
                     created (weaver/op!
                              rt 'agent
                              ["run" "pi" "--interactive" "--cwd" "/tmp"])
                     first-start
                     (execution/mark-interactive-running!
                      rt (:id created) pid)
                     first-invocation
                     (spool/attr-get first-start :harness/invocation)
                     _ (execution/mark-interactive-provider!
                        rt (:id created) first-invocation pid)
                     first-finish
                     (execution/finish-interactive!
                      rt (:id created) first-invocation 1)
                     retried (harnesses/retry! rt (:id created) {})
                     _ (execution/prepare-interactive! rt retried)
                     second-start
                     (execution/mark-interactive-running!
                      rt (:id created) pid)
                     second-invocation
                     (spool/attr-get second-start :harness/invocation)
                     _ (execution/mark-interactive-provider!
                        rt (:id created) second-invocation pid)
                     running (weaver/show rt (:id created))
                     stale-registration
                     (try
                       (execution/mark-interactive-provider!
                        rt (:id created) first-invocation pid)
                       nil
                       (catch clojure.lang.ExceptionInfo error
                         (ex-message error)))
                     stale-registration-no-write
                     (= running (weaver/show rt (:id created)))
                     missing-finish
                     (try
                       (execution/finish-interactive!
                        rt (:id created) nil 1)
                       nil
                       (catch clojure.lang.ExceptionInfo error
                         (ex-message error)))
                     missing-finish-no-write
                     (= running (weaver/show rt (:id created)))
                     _ (execution/finish-interactive!
                        rt (:id created) first-invocation 1)
                     stale-finish-no-write
                     (= running (weaver/show rt (:id created)))
                     exact-finish
                     (execution/finish-interactive!
                      rt (:id created) second-invocation 1)]
                 {:first
                  {:status (spool/attr-get first-finish :harness/status)
                   :settled (spool/attr-get first-finish :harness/settled)}
                  :attempts
                  [(spool/attr-get first-start :harness/attempt)
                   (spool/attr-get second-start :harness/attempt)]
                  :invocations-differ
                  (not= first-invocation second-invocation)
                  :guidance-states
                  (mapv #(or (get % "state") (get % :state))
                        (or (spool/attr-get
                             running :harness/guidance-attempts)
                            []))
                  :second-provider-fenced
                  (= second-invocation
                     (spool/attr-get running :harness/provider-invocation))
                  :custody-states
                  [(:state (reconcile/completion-owner-observation running))
                   (:state (reconcile/provider-observation running))]
                  :second-callback-contract
                  (spool/attr-get running
                                  :harness/interactive-callback-contract)
                  :stale-registration stale-registration
                  :stale-registration-no-write stale-registration-no-write
                  :missing-finish missing-finish
                  :missing-finish-no-write missing-finish-no-write
                  :stale-finish-no-write stale-finish-no-write
                  :exact-finish
                  {:status (spool/attr-get exact-finish :harness/status)
                   :settled (spool/attr-get exact-finish :harness/settled)}})))]
       (is (= {:status "failed" :settled "true"} (:first result)))
       (is (= [1 2] (:attempts result)))
       (is (true? (:invocations-differ result)))
       (is (= []
              (:guidance-states result)))
       (is (= "v2" (:second-callback-contract result)))
       (is (true? (:second-provider-fenced result)))
       (is (= ["live" "live"] (:custody-states result)))
       (is (re-find #"stale interactive invocation"
                    (:stale-registration result)))
       (is (true? (:stale-registration-no-write result)))
       (is (re-find #"requires a legacy attempt" (:missing-finish result)))
       (is (true? (:missing-finish-no-write result)))
       (is (true? (:stale-finish-no-write result)))
       (is (= {:status "failed" :settled "true"}
              (:exact-finish result)))))))

(deftest serialized-finish-rejects-a-retired-invocation-after-retry
  (test-alpha/run-with-weaver-world
   (full-world-options)
   (fn [ctx]
     (let [result
           (test-alpha/repl!
            ctx
            '(do
               (require '[ct.spools.harnesses :as harnesses]
                        '[ct.spools.harnesses.execution :as execution]
                        '[ct.spools.harnesses.providers.pi :as pi]
                        '[millhouse.spools.identity :as identity]
                        '[millstrand.api.current.alpha :as current]
                        '[millstrand.api.spool.alpha :as spool]
                        '[millstrand.api.weaver.alpha :as weaver])
               (let [rt (current/runtime)
                     pid (.pid (java.lang.ProcessHandle/current))
                     created (weaver/op!
                              rt 'agent
                              ["run" "pi" "--interactive" "--cwd" "/tmp"])
                     started
                     (execution/mark-interactive-running!
                      rt (:id created) pid)
                     invocation
                     (spool/attr-get started :harness/invocation)
                     _ (execution/mark-interactive-provider!
                        rt (:id created) invocation pid)
                     entered (promise)
                     release (promise)
                     snapshot
                     (fn []
                       (let [run (weaver/show rt (:id created))]
                         {:run run
                          :identity
                          (spool/attr-get run :identity/id)
                          :strand-count (count (weaver/list rt))}))]
                 (with-redefs
                  [pi/finish
                   (fn [_rt _definition _run _observed]
                     (deliver entered :entered)
                     @release
                     {:status :failed
                      :exit-code 1
                      :error "delayed attempt-1 completion"})]
                   (let [delayed
                         (future
                           (try
                             {:returned
                              (execution/finish-interactive!
                               rt (:id created) invocation 1)}
                             (catch Throwable error
                               {:error (ex-message error)
                                :data (ex-data error)})))
                         entered-result (deref entered 5000 :timeout)
                         _ (when (= :timeout entered-result)
                             (deliver release :release)
                             (throw (ex-info "Delayed callback did not enter"
                                             {})))
                         race-state
                         (try
                           (harnesses/finish!
                            rt (:id created)
                            {:status :failed
                             :exit-code 1
                             :invocation invocation
                             :evidence {:settled true
                                        :settlement "process-exit"}
                             :error "competing completion"})
                           (harnesses/retry! rt (:id created) {})
                           {:before (snapshot)}
                           (finally
                             (deliver release :release)))
                         delayed-result (deref delayed 5000 :timeout)
                         after (snapshot)
                         before (:before race-state)
                         before-run (:run before)]
                     {:entered entered-result
                      :delayed-result delayed-result
                      :unchanged (= before after)
                      :ready-state
                      [(spool/attr-get before-run :harness/status)
                       (spool/attr-get before-run :harness/substatus)
                       (spool/attr-get before-run :harness/invocation)
                       (spool/attr-get before-run :harness/settled)]
                      :retained-custody
                      [(spool/attr-get before-run
                                       :harness/completion-owner-invocation)
                       (spool/attr-get before-run
                                       :harness/provider-invocation)]})))))]
       (is (= :entered (:entered result)))
       (is (re-find #"retired invocation token"
                    (get-in result [:delayed-result :error])))
       (is (true? (:unchanged result)))
       (is (= ["ready" "pending" nil nil] (:ready-state result)))
       (is (= 2 (count (filter string? (:retained-custody result)))))
       (is (apply = (:retained-custody result)))))))

(deftest final-finish-fence-preserves-supported-ready-and-current-outcomes
  (test-alpha/run-with-weaver-world
   (full-world-options)
   (fn [ctx]
     (let [result
           (test-alpha/repl!
            ctx
            '(do
               (require '[ct.spools.harnesses :as harnesses]
                        '[ct.spools.harnesses.reconciliation :as reconcile]
                        '[millstrand.api.current.alpha :as current]
                        '[millstrand.api.spool.alpha :as spool]
                        '[millstrand.api.weaver.alpha :as weaver])
               (let [rt (current/runtime)
                     stale (harnesses/create!
                            rt {:harness :cursor :mode :interactive})
                     stale-before (weaver/show rt (:id stale))
                     stale-error
                     (try
                       (harnesses/finish!
                        rt (:id stale)
                        {:status :failed
                         :exit-code 1
                         :invocation "retired-attempt"
                         :evidence {:settled true
                                    :settlement "process-exit"}})
                       nil
                       (catch clojure.lang.ExceptionInfo error
                         (ex-message error)))
                     stale-unchanged
                     (= stale-before (weaver/show rt (:id stale)))
                     current (harnesses/create!
                              rt {:harness :cursor :mode :interactive})
                     current-start
                     (harnesses/begin-attempt!
                      rt (:id current)
                      (reconcile/completion-owner-attributes
                       (.pid (java.lang.ProcessHandle/current))))
                     exact
                     (harnesses/finish!
                      rt (:id current)
                      {:status :failed
                       :exit-code 1
                       :invocation (:invocation current-start)
                       :evidence {:settled true
                                  :settlement "process-exit"}})
                     fresh (harnesses/create!
                            rt {:harness :cursor :mode :interactive})
                     fresh
                     (harnesses/finish!
                      rt (:id fresh)
                      {:status :failed
                       :exit-code 1
                       :evidence {:settled true
                                  :settlement "process-exit"}})]
                 {:stale-error stale-error
                  :stale-unchanged stale-unchanged
                  :exact
                  [(spool/attr-get exact :harness/status)
                   (spool/attr-get exact :harness/settled)
                   (spool/attr-get exact :harness/invocation)]
                  :exact-invocation (:invocation current-start)
                  :fresh
                  [(spool/attr-get fresh :harness/status)
                   (spool/attr-get fresh :harness/settled)
                   (spool/attr-get fresh :harness/invocation)]})))]
       (is (re-find #"retired invocation token" (:stale-error result)))
       (is (true? (:stale-unchanged result)))
       (is (= ["failed" "true" (:exact-invocation result)]
              (:exact result)))
       (is (= ["failed" "true" nil] (:fresh result)))))))

(deftest invocation-less-completion-is-limited-to-legacy-attempts
  (test-alpha/run-with-weaver-world
   (full-world-options)
   (fn [ctx]
     (let [result
           (test-alpha/repl!
            ctx
            '(do
               (require '[ct.spools.harnesses.execution :as execution]
                        '[millstrand.api.current.alpha :as current]
                        '[millstrand.api.spool.alpha :as spool]
                        '[millstrand.api.weaver.alpha :as weaver])
               (let [rt (current/runtime)
                     pid (.pid (java.lang.ProcessHandle/current))
                     created (weaver/op!
                              rt 'agent
                              ["run" "pi" "--interactive" "--cwd" "/tmp"])
                     started
                     (execution/mark-interactive-running! rt (:id created))
                     _ (execution/mark-interactive-provider!
                        rt (:id created) nil pid)
                     before-finish (weaver/show rt (:id created))
                     finished
                     (execution/finish-interactive! rt (:id created) nil 1)]
                 {:callback-contract
                  (spool/attr-get before-finish
                                  :harness/interactive-callback-contract)
                  :attempt (spool/attr-get before-finish :harness/attempt)
                  :invocation (spool/attr-get before-finish
                                              :harness/invocation)
                  :provider-fenced
                  (= (spool/attr-get started :harness/invocation)
                     (spool/attr-get before-finish
                                     :harness/provider-invocation))
                  :finished
                  {:status (spool/attr-get finished :harness/status)
                   :settled (spool/attr-get finished :harness/settled)}})))]
       (testing "old-bin callbacks remain valid after a backend upgrade"
         (is (= "legacy" (:callback-contract result)))
         (is (= 1 (:attempt result)))
         (is (string? (:invocation result)))
         (is (true? (:provider-fenced result)))
         (is (= {:status "failed" :settled "true"}
                (:finished result))))))))

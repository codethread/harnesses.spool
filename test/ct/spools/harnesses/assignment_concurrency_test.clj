(ns ct.spools.harnesses.assignment-concurrency-test
  "Continuation, launch failure, and scheduler ownership assignment tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.assignment :as assignment]
            [ct.spools.harnesses.assignment-test :as fixture]
            [ct.spools.harnesses.execution :as execution]))

(deftest native-resume-reapplies-all-caller-guidance-on-current-invocation
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [alias-guidance "Existing alias guidance."
                    explicit-guidance "Explicit caller guidance."
                    _ (harnesses/register-alias!
                       rt :guided-pi
                       {:doc "Use Pi with existing guidance."
                        :parent :pi
                        :append-system-prompt alias-guidance
                        :attributes {}})
                    card (add-target! "Resume guidance")
                    first (assign! (:id card)
                                   {:harness :guided-pi
                                    :policy "close-on-complete"
                                    :append-system-prompt explicit-guidance})
                    first-id (:id first)
                    started (harnesses/begin-attempt! rt first-id)
                    native (harnesses/register-native-session!
                            rt {:harness "pi"
                                :native-session-id (attr first :harness/session-id)
                                :cwd "/tmp/assignment-work"
                                :run-id first-id})
                    _ (claim! (:id card) (:identity native)
                              :branch "resume-owner"
                              :worktree "/tmp/assignment-work"
                              :run-id first-id)
                    _ (harnesses/finish!
                       rt first-id
                       {:status :done :exit-code 0 :result "done"
                        :session-id (attr first :harness/session-id)
                        :session-usable true
                        :invocation (:invocation started)})
                    _ (assignment/register-assign-policy!
                       rt {:kind :assign-policy
                           :name :close-on-complete
                           :text "Changed live policy text"})
                    resumed (harnesses/resume!
                             rt first-id {:prompt "New user primer"})
                    _ (harnesses/begin-attempt! rt (:id resumed))
                    resumed-native (harnesses/register-native-session!
                                    rt {:harness "pi" :run-id (:id resumed)
                                        :cwd "/tmp/assignment-work"
                                        :native-session-id (attr resumed :harness/session-id)})
                    argv (:argv (pi/prepare rt (pi/harness rt) resumed))
                    command (str/join " " argv)]
                {:first first-id
                 :resumed (:id resumed)
                 :argv command
                 :policy-count
                 (count (re-seq #"close the assigned work target yourself" command))
                 :alias-count (count (re-seq #"Existing alias guidance\." command))
                 :explicit-count
                 (count (re-seq #"Explicit caller guidance\." command))
                 :same-identity (= (:identity native) (:identity resumed-native))
                 :claim-count (count (kanban/ownership-history rt (:id card)))
                 :stdin (:stdin (pi/prepare rt (pi/harness rt) resumed))}))]
        (is (= 1 (:policy-count result)))
        (is (= 1 (:alias-count result)))
        (is (= 1 (:explicit-count result)))
        (is (not (str/includes? (:argv result) "#object[")))
        (is (str/includes? (:argv result) (:resumed result)))
        (is (not (str/includes? (:argv result) (:first result))))
        (is (not (str/includes? (:argv result) "strand kanban claim")))
        (is (true? (:same-identity result)))
        (is (= 1 (:claim-count result)))
        (is (= "New user primer\n" (:stdin result)))))))

(deftest fresh-continuation-binds-lineage-head
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [card (add-target! "Fresh lineage")
                    parent (assign! (:id card) {:request-id "parent"})
                    _ (harnesses/finish!
                       rt (:id parent)
                       {:status :done :exit-code 0 :result "parent"})
                    child (assign! (:id card)
                                   {:after (:id parent)
                                    :request-id "child"})
                    continues (graph/outgoing-edges rt [(:id child)]
                                                    "continues")
                    edge-types (mapv :edge_type continues)
                    _ (harnesses/finish!
                       rt (:id child)
                       {:status :done :exit-code 0 :result "child"
                        :session-usable true})
                    stale-fresh (try
                                  (assign! (:id card)
                                           {:after (:id parent)
                                            :request-id "stale-fresh"})
                                  :accepted
                                  (catch Exception e (ex-message e)))
                    stale-native (try
                                   (harnesses/resume! rt (:id parent) {})
                                   :accepted
                                   (catch Exception e (ex-message e)))
                    selected (harnesses/resolve-resume-run
                              rt {:logical-id
                                  (attr child :harness/logical-id)})]
                {:parent (:id parent)
                 :child (:id child)
                 :after (attr child :harness/after)
                 :edge-types edge-types
                 :continues continues
                 :parent-logical (attr parent :harness/logical-id)
                 :child-logical (attr child :harness/logical-id)
                 :continued (attr parent :harness/continued)
                 :stale-fresh stale-fresh
                 :stale-native stale-native
                 :selected (:id selected)}))]
        (is (= (:parent result) (:after result)))
        (is (some #{"continues"} (:edge-types result)))
        (is (re-find #"already has an accepted continuation"
                     (:stale-fresh result)))
        (is (re-find #"already has an accepted continuation"
                     (:stale-native result)))
        (is (= (:child result) (:selected result)))))))

(deftest only-typed-malformed-launch-settles-without-custody
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(do
                (defn launch-prepare [_rt _definition _run]
                  {:argv ["/bin/true"] :env {} :stdin nil})
                (defn launch-finish [_rt _definition _run _observed]
                  {:status :done :exit-code 0 :result "unexpected"})
                (harnesses/register-harness!
                 rt :launch-test
                 {:modes #{:headless}
                  :prepare (symbol (str (ns-name *ns*)) "launch-prepare")
                  :finish (symbol (str (ns-name *ns*)) "launch-finish")})
                (let [opened (#'execution/activate-state! rt)
                      launch-error (atom nil)]
                  (try
                    (let [failures
                          (with-redefs-fn
                            {(ns-resolve
                              'ct.spools.harnesses.internal.process-custody
                              'launch!)
                             (fn [& _] (throw @launch-error))
                             #'execution/inspect-owned! (constantly nil)
                             #'execution/schedule! (constantly [])}
                            #(mapv
                              (fn [[case error]]
                                (let [target (add-target! (str "Launch " case))
                                      run (assign! (:id target)
                                                   {:harness :launch-test
                                                    :request-id (str "launch-" case)})]
                                  (reset! launch-error error)
                                  (#'execution/launch-headless! rt (:id run))
                                  (let [failed (weaver/show rt (:id run))]
                                    {:case case
                                     :status (attr failed :harness/status)
                                     :substatus (attr failed :harness/substatus)
                                     :settled (attr failed :harness/settled)
                                     :settlement (attr failed :harness/settlement)})))
                              [["malformed"
                                (ex-info "cwd does not exist"
                                         {:code "process/malformed-launch"})]
                               ["control"
                                (ex-info "control unavailable"
                                         {:code "process/control-unavailable"})]
                               ["generic"
                                (ex-info "generic process failure"
                                         {:code "process/error"})]
                               ["conflict"
                                (ex-info "reservation conflict"
                                         {:code "process/conflicting-key"})]
                               ["untyped"
                                (ex-info "ambiguous launch failure" {})]]))]
                      (test-alpha/await-quiescent! rt)
                      failures)
                    (finally
                      ((:close-fn (#'execution/deactivate-state! rt))))))))]
        (is (= [{:case "malformed"
                 :status "failed"
                 :substatus "launch"
                 :settled "true"
                 :settlement "launch-failure"}
                {:case "control"
                 :status "failed"
                 :substatus "execution"
                 :settled "false"
                 :settlement "no-terminal-evidence"}
                {:case "generic"
                 :status "failed"
                 :substatus "execution"
                 :settled "false"
                 :settlement "no-terminal-evidence"}
                {:case "conflict"
                 :status "failed"
                 :substatus "execution"
                 :settled "false"
                 :settlement "no-terminal-evidence"}
                {:case "untyped"
                 :status "failed"
                 :substatus "execution"
                 :settled "false"
                 :settlement "no-terminal-evidence"}]
               result))))))

(deftest concurrent-schedulers-and-stale-workers-cannot-share-ownership
  (let [run {:id "run-1"}
        in-flight (atom #{})
        ready-entered (java.util.concurrent.CountDownLatch. 2)
        ready-release (java.util.concurrent.CountDownLatch. 1)
        validation-entered (java.util.concurrent.CountDownLatch. 2)
        validation-release (java.util.concurrent.CountDownLatch. 1)
        validation-calls (atom 0)
        submissions (atom [])
        executor (reify java.util.concurrent.Executor
                   (execute [_ runnable]
                     (swap! submissions conj runnable)))
        scheduler-state {:in-flight in-flight :executor executor}]
    ;; Hold both atom validators after their pure updates have observed the
    ;; empty set. One swap must retry after the other commits.
    (set-validator! in-flight
                    (fn [_]
                      ;; Installing a validator checks the atom's current value.
                      ;; Synchronize the next two calls, which come from swaps.
                      (when (> (swap! validation-calls inc) 1)
                        (.countDown validation-entered)
                        (.await validation-release))
                      true))
    (with-redefs-fn
      {(ns-resolve 'ct.spools.harnesses.execution 'ready-headless)
       (fn [_]
         (.countDown ready-entered)
         (.await ready-release)
         [run])
       (ns-resolve 'ct.spools.harnesses.execution 'state)
       (constantly scheduler-state)}
      #(let [first-schedule (future (execution/schedule! {}))
             second-schedule (future (execution/schedule! {}))]
         (is (.await ready-entered 1 java.util.concurrent.TimeUnit/SECONDS))
         (.countDown ready-release)
         (is (.await validation-entered 1 java.util.concurrent.TimeUnit/SECONDS))
         (.countDown validation-release)
         (let [results [@first-schedule @second-schedule]]
           (is (= 1 (count (filter seq results))))
           (is (= 1 (count @submissions)))
           (is (= #{"run-1"} @in-flight)))))
    (set-validator! in-flight nil))
  (let [run-id "stale-run"
        phase (atom "ready")
        in-flight (atom #{})
        queued (atom [])
        ready-calls (atom 0)
        attempts (atom 0)
        failures (atom 0)
        executor (reify java.util.concurrent.Executor
                   (execute [_ runnable]
                     (swap! queued conj runnable)))
        scheduler-state {:in-flight in-flight :executor executor}]
    (with-redefs-fn
      {(ns-resolve 'ct.spools.harnesses.execution 'ready-headless)
       (fn [_]
         (if (= 1 (swap! ready-calls inc))
           [{:id run-id}]
           []))
       (ns-resolve 'ct.spools.harnesses.execution 'state)
       (constantly scheduler-state)
       (ns-resolve 'ct.spools.harnesses.execution 'state-holder)
       (constantly {:active (atom scheduler-state)})
       (ns-resolve 'ct.spools.harnesses.execution 'full-run)
       (fn [_ _]
         {:id run-id
          :attributes {:harness/status @phase}})
       #'assignment/launch-ready? (constantly true)
       #'harnesses/begin-attempt!
       (fn [& _]
         (swap! attempts inc)
         {:attempt 1 :invocation "stolen"})
       #'harnesses/finish!
       (fn [& _]
         (swap! failures inc))
       (ns-resolve 'ct.spools.harnesses.execution 'inspect-owned!)
       (constantly nil)}
      #(do
         (is (= [run-id] (execution/schedule! {})))
         ;; Model a stale queued worker running after the prior claim was
         ;; released and another owner durably entered its attempt.
         (reset! in-flight #{})
         (reset! phase "running")
         (.run ^Runnable (first @queued))
         (is (zero? @attempts))
         (is (zero? @failures))
         (is (= "running" @phase))))))

(deftest public-scheduler-claims-only-ready-assignments
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [blocker (add-target! "Scheduling blocker")
                    blocked (add-target!
                             "Blocked assignment" {}
                             [{:type "depends-on" :to (:id blocker)}])
                    independent (add-target! "Independent assignment")
                    blocked-run (assign! (:id blocked) {})
                    independent-run (assign! (:id independent) {})
                    opened (#'execution/activate-state! rt)
                    launched (atom [])
                    claimed
                    (try
                      (with-redefs-fn
                        {#'execution/launch-headless!
                         (fn [_ id] (swap! launched conj id))}
                        #(let [first-claimed (execution/schedule! rt)
                               _ (Thread/sleep 50)
                               _ (weaver/update! rt (:id blocker)
                                                 {:state "closed"})
                               second-claimed (execution/schedule! rt)
                               _ (Thread/sleep 50)]
                           {:first first-claimed
                            :second second-claimed}))
                      (finally ((:close-fn opened))))]
                {:claimed claimed
                 :launched @launched
                 :blocked (:id blocked-run)
                 :independent (:id independent-run)}))]
        (is (= [(:independent result)] (get-in result [:claimed :first])))
        (is (= [(:blocked result)] (get-in result [:claimed :second])))
        (is (= #{(:blocked result) (:independent result)}
               (set (:launched result))))))))

(deftest burning-a-blocker-wakes-its-assigned-agent
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [blocker (add-target! "Removed blocker")
                    target (add-target! "Waiting assignment" {}
                                        [{:type "depends-on" :to (:id blocker)}])
                    run (assign! (:id target) {})
                    launched (promise)
                    _ (#'execution/activate-state! rt)]
                (try
                  (with-redefs-fn
                    {#'execution/launch-headless!
                     (fn [_ id] (deliver launched id))}
                    #(do
                       (test-alpha/await-quiescent! rt)
                       (runtime/module! rt :assignment-scheduler
                                        {:file "modules/assignment_scheduler.clj"
                                         :after [:assignment-test]
                                         :required? true})
                       (test-alpha/await-quiescent! rt)
                       (let [launched-before? (realized? launched)]
                         (graph/burn-by-ids! rt [(:id blocker)])
                         {:launched-before? launched-before?
                          :ready? (assignment/target-ready? rt (:id target))
                          :expected (:id run)
                          :launched (deref launched 1000 :not-launched)})))
                  (finally
                    ((:close-fn (#'execution/deactivate-state! rt)))))))]
        (is (false? (:launched-before? result)))
        (is (true? (:ready? result)))
        (is (= (:expected result) (:launched result)))))))

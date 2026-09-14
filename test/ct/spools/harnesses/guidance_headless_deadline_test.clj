(ns ct.spools.harnesses.guidance-headless-deadline-test
  "Generation fencing for scheduled headless guidance expiry."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest headless-expiry-and-retirement-have-two-serialized-outcomes
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(do
                 (require '[ct.spools.harnesses.internal.process-custody
                            :as custody]
                          '[ct.spools.harnesses.internal.runs :as runs])
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* accepted-runner]
                   (let [execution-state
                         (deref
                          (ns-resolve 'ct.spools.harnesses.execution 'state))
                         schedule-inspection
                         (deref
                          (ns-resolve 'ct.spools.harnesses.execution
                                      'schedule-inspection!))
                         inspection-hook
                         (ns-resolve 'ct.spools.harnesses.execution
                                     'guidance-deadline-hook!)
                         active-run (atom nil)
                         inspect-enabled? (atom false)
                         hook-mode (atom nil)
                         events (atom [])
                         entered (atom nil)
                         release (atom nil)
                         prepare-run
                         (fn [millis]
                           (let [created
                                 (harnesses/create!
                                  rt {:harness :native-codex
                                      :mode :headless
                                      :cwd "/tmp"
                                      :prompt "Headless deadline fixture"
                                      :guidance-transport "native-v1"})
                                 started
                                 (harnesses/begin-attempt! rt (:id created))
                                 record
                                 (guidance/current-attempt (:strand started))
                                 deadline
                                 (str (.plusMillis
                                       (java.time.Instant/now) millis))
                                 process-key
                                 (str (:id created) "/attempt-"
                                      (:attempt started))]
                             {:run
                              (weaver/update!
                               rt (:id created)
                               {:attributes
                                {:harness/guidance-attempts
                                 [(assoc record "deadline-at" deadline)]
                                 :harness/process-owner "agent-harness/run"
                                 :harness/process-key process-key
                                 :harness/process-handle "fixture-handle"}})
                              :deadline deadline
                              :record {:owner custody/owner
                                       :key process-key
                                       :handle "fixture-handle"
                                       :phase :running}}))
                         current-record
                         (fn []
                           (:record @active-run))
                         inspectable
                         (fn [_ _]
                           (if @inspect-enabled?
                             [(weaver/show rt (:id (:run @active-run)))]
                             []))
                         hook
                         (fn [event]
                           (swap! events conj event)
                           (let [phase (:phase event)]
                             (when (and
                                    (= :expiry-wins @hook-mode)
                                    (= :headless-after-reload phase)
                                    (= (:id (:run @active-run))
                                       (:run-id event)))
                               (.countDown ^java.util.concurrent.CountDownLatch
                                @entered)
                               (.await ^java.util.concurrent.CountDownLatch
                                @release))
                             (when (and
                                    (= :retirement-wins @hook-mode)
                                    (= :headless-before-publication-lock phase))
                               (.countDown ^java.util.concurrent.CountDownLatch
                                @entered)
                               (loop []
                                 (let [released?
                                       (try
                                         (.await
                                          ^java.util.concurrent.CountDownLatch
                                          @release)
                                         true
                                         (catch InterruptedException _ false))]
                                   (when-not released? (recur)))))))]
                     (with-redefs-fn
                       {#'runs/inspectable-headless inspectable
                        #'custody/list-owned (fn [_] [(current-record)])
                        #'custody/cancel! (fn [& _] nil)
                        inspection-hook hook}
                       (fn []
                         (let [expiry (prepare-run -1)
                               _ (reset! active-run expiry)
                               _ (reset! hook-mode :expiry-wins)
                               _ (reset! entered
                                         (java.util.concurrent.CountDownLatch. 1))
                               _ (reset! release
                                         (java.util.concurrent.CountDownLatch. 1))
                               _ (execution/open-execution! {:runtime rt})
                               expiry-opened (execution-state rt)
                               _ (reset! inspect-enabled? true)
                               _ (schedule-inspection rt expiry-opened)
                               _ (when-not
                                  (.await
                                   ^java.util.concurrent.CountDownLatch @entered
                                   3 java.util.concurrent.TimeUnit/SECONDS)
                                   (throw (ex-info
                                           "Headless expiry did not reach reload"
                                           {})))
                               close-started
                               (java.util.concurrent.CountDownLatch. 1)
                               closing
                               (future
                                 (.countDown close-started)
                                 (execution/close-execution! {:runtime rt}))
                               _ (.await close-started)
                               close-blocked (deref closing 25 :blocked)
                               _ (.countDown
                                  ^java.util.concurrent.CountDownLatch @release)
                               _ @closing
                               expiry-after
                               (weaver/show rt (:id (:run expiry)))
                               retirement (prepare-run 350)
                               _ (reset! active-run retirement)
                               _ (reset! hook-mode :retirement-wins)
                               _ (reset! inspect-enabled? false)
                               _ (reset! entered
                                         (java.util.concurrent.CountDownLatch. 1))
                               _ (reset! release
                                         (java.util.concurrent.CountDownLatch. 1))
                               _ (execution/open-execution! {:runtime rt})
                               old-opened (execution-state rt)
                               before-close
                               (weaver/show rt (:id (:run retirement)))
                               _ (reset! inspect-enabled? true)
                               _ (schedule-inspection rt old-opened)
                               _ (when-not
                                  (.await
                                   ^java.util.concurrent.CountDownLatch @entered
                                   3 java.util.concurrent.TimeUnit/SECONDS)
                                   (throw (ex-info
                                           "Headless inspection did not pause"
                                           {})))
                               _ (execution/close-execution! {:runtime rt})
                               after-close
                               (weaver/show rt (:id (:run retirement)))
                               _ (reset! inspect-enabled? false)
                               _ (execution/open-execution! {:runtime rt})
                               new-opened (execution-state rt)
                               after-reopen
                               (weaver/show rt (:id (:run retirement)))
                               _ (.countDown
                                  ^java.util.concurrent.CountDownLatch @release)
                               _ (.awaitTermination
                                  ^java.util.concurrent.ScheduledExecutorService
                                  (:scheduler old-opened)
                                  3 java.util.concurrent.TimeUnit/SECONDS)
                               after-old-resumed
                               (weaver/show rt (:id (:run retirement)))
                               _ (reset! hook-mode nil)
                               _ (reset! inspect-enabled? true)
                               _ (schedule-inspection rt new-opened)
                               recovered
                               (loop [remaining 300]
                                 (let [run
                                       (weaver/show rt (:id (:run retirement)))]
                                   (if (or (= "failed"
                                              (attr run :harness/status))
                                           (zero? remaining))
                                     run
                                     (do (Thread/sleep 10)
                                         (recur (dec remaining))))))
                               _ (execution/close-execution! {:runtime rt})]
                           {:expiry
                            {:close-blocked close-blocked
                             :status (attr expiry-after :harness/status)
                             :substatus (attr expiry-after :harness/substatus)}
                            :retirement
                            {:same-after-close (= before-close after-close)
                             :same-after-reopen (= before-close after-reopen)
                             :same-after-old-resumed
                             (= before-close after-old-resumed)
                             :deadline
                             (get (guidance/current-attempt after-old-resumed)
                                  "deadline-at")
                             :expected-deadline (:deadline retirement)
                             :status (attr recovered :harness/status)
                             :substatus (attr recovered :harness/substatus)
                             :old-generation (:generation old-opened)
                             :new-generation (:generation new-opened)}
                            :events @events}))))))))]
        (is (= :blocked (get-in result [:expiry :close-blocked])))
        (is (= ["failed" "bootstrap"]
               [(get-in result [:expiry :status])
                (get-in result [:expiry :substatus])]))
        (is (true? (get-in result [:retirement :same-after-close])))
        (is (true? (get-in result [:retirement :same-after-reopen])))
        (is (true? (get-in result [:retirement :same-after-old-resumed])))
        (is (= (get-in result [:retirement :expected-deadline])
               (get-in result [:retirement :deadline])))
        (is (not= (get-in result [:retirement :old-generation])
                  (get-in result [:retirement :new-generation])))
        (is (= ["failed" "bootstrap"]
               [(get-in result [:retirement :status])
                (get-in result [:retirement :substatus])]))
        (let [old (get-in result [:retirement :old-generation])
              old-events (filter #(= old (:generation %)) (:events result))]
          (is (= :headless-before-publication-lock
                 (:phase (last old-events)))))))))

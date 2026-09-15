(ns ct.spools.harnesses.guidance-process-deadline-test
  "Real-process deadline and first-acquisition custody regressions."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.guidance-capability-test]
            [ct.spools.harnesses.internal.guidance-authority :as authority]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline]
            [ct.spools.harnesses.internal.guidance-process :as process]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity]
            [ct.spools.harnesses.internal.guidance-process-retirement
             :as retirement])
  (:import [java.util.concurrent TimeUnit]))

(defn- with-profile [operation]
  ((deref
    (ns-resolve 'ct.spools.harnesses.guidance-capability-test 'with-profile))
   "codex" operation))

(defn- timed-failure [operation]
  (let [started (System/nanoTime)
        failure (try (operation) nil (catch Throwable error error))]
    {:failure failure
     :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)}))

(defn- worker-count [name]
  (->> (.keySet (Thread/getAllStackTraces))
       (filter #(and (.isAlive ^Thread %)
                     (= name (.getName ^Thread %))))
       count))

(defn- run-with-retain [profile retain-operation]
  (let [direct-supervisor (atom nil)
        original-direct identity/retain-direct
        result
        (with-redefs [identity/retain-direct
                      (fn [& args]
                        (let [retained (apply original-direct args)
                              role (second args)]
                          (when (= "direct-supervisor" role)
                            (reset! direct-supervisor retained))
                          retained))
                      identity/retain retain-operation]
          (timed-failure
           #(process/run! (assoc profile :effective-environment {})
                          "{\"probe\":true}"
                          (deadline/start)
                          identity)))]
    (assoc result :direct-supervisor @direct-supervisor)))

(deftest timed-out-owned-operation-cannot-signal-after-cancellation
  (let [now (System/nanoTime)
        budget {:started-at now
                :work-deadline (+ now 80000000)
                :deadline (+ now 300000000)}
        entered (promise)
        signals (atom 0)
        retained {:pid 81
                  :direct? true
                  :alive?
                  #(do
                     (deliver entered true)
                     (try
                       (Thread/sleep 1000)
                       (catch InterruptedException _ nil))
                     true)
                  :destroy! #(do (swap! signals inc) true)}
        {error :failure elapsed-ms :elapsed-ms}
        (timed-failure
         #(deadline/owned!
           budget "late-signal"
           (fn [operation-authority]
             (identity/signal! retained operation-authority))))]
    (is (deref entered 100 false))
    (is (re-find #"Guidance preflight timed out" (ex-message error)))
    (is (< elapsed-ms 300.0))
    (is (zero? @signals))
    (is (zero? (worker-count "guidance-admission-worker")))))

(deftest direct-retirement-rechecks-authority-after-delayed-liveness
  (let [process (.start (ProcessBuilder.
                         ^java.util.List ["/bin/sleep" "30"]))
        now (System/nanoTime)
        budget {:started-at now
                :work-deadline (+ now 80000000)
                :deadline (+ now 300000000)}
        entered (promise)
        survived? (atom nil)
        result
        (try
          (let [result
                (with-redefs-fn
                  {(ns-resolve
                    'ct.spools.harnesses.internal.guidance-process-retirement
                    'process-live?)
                   (fn [_]
                     (deliver entered true)
                     (try
                       (Thread/sleep 1000)
                       (catch InterruptedException _ nil))
                     true)}
                  #(timed-failure
                    (fn []
                      (deadline/owned!
                       budget "direct-retirement"
                       (fn [operation-authority]
                         (retirement/release-process!
                          process operation-authority (:work-deadline budget)
                          deadline/remaining-nanos))))))]
            (reset! survived? (.isAlive process))
            result)
          (finally
            (when (.isAlive process)
              (.destroyForcibly process))))]
    (is (deref entered 100 false))
    (is (re-find #"Guidance preflight timed out"
                 (ex-message (:failure result))))
    (is (< (:elapsed-ms result) 300.0))
    (is (true? @survived?))
    (is (.waitFor process 2 TimeUnit/SECONDS))
    (is (zero? (worker-count "guidance-admission-worker")))))

(deftest cancelled-birth-probe-cannot-promote-authority
  (let [now (System/nanoTime)
        budget {:started-at now
                :work-deadline (+ now 80000000)
                :deadline (+ now 300000000)}
        entered (promise)
        promotions (atom 0)
        {error :failure elapsed-ms :elapsed-ms}
        (timed-failure
         #(deadline/owned!
           budget "late-promotion"
           (fn [operation-authority]
             (deliver entered true)
             (try
               (Thread/sleep 1000)
               (catch InterruptedException _ nil))
             (authority/run! operation-authority "late-promotion"
                             (fn [] (swap! promotions inc))))))]
    (is (deref entered 100 false))
    (is (re-find #"Guidance preflight timed out" (ex-message error)))
    (is (< elapsed-ms 300.0))
    (is (zero? @promotions))
    (is (zero? (worker-count "guidance-admission-worker")))))

(deftest proven-launch-children-survive-later-acquisition-failure
  (with-profile
    (fn [{:keys [profile]}]
      (doseq [failed-role ["ownership-anchor" "preflight-helper"]]
        (testing failed-role
          (let [sentinel (.start (ProcessBuilder.
                                  ^java.util.List ["/bin/sleep" "30"]))
                proven (atom nil)
                sentinel-survived? (atom nil)
                original-retain-child! identity/retain-child!
                failure
                (try
                  (let [result
                        (with-redefs
                         [identity/retain-child!
                          (fn [parent pid role confirmed!]
                            (let [child (original-retain-child!
                                         parent pid role confirmed!)]
                              (when (= failed-role role)
                                (reset! proven child)
                                (throw (ex-info "injected post-proof failure"
                                                {:role role})))
                              child))]
                          (:failure
                           (timed-failure
                            #(process/run!
                              (assoc profile :effective-environment {})
                              "{\"probe\":true}"
                              (deadline/start)
                              identity))))]
                    (reset! sentinel-survived? (.isAlive sentinel))
                    result)
                  (finally
                    (when (.isAlive sentinel)
                      (.destroyForcibly sentinel))))]
            (is (re-find #"injected post-proof failure" (ex-message failure)))
            (is @proven)
            (is (not (identity/live? @proven)))
            (is (true? @sentinel-survived?))
            (is (.waitFor sentinel 2 TimeUnit/SECONDS))))))))

(deftest supervisor-identity-is-bounded-and-direct-process-custody-survives
  (with-profile
    (fn [{:keys [profile]}]
      (let [original-retain identity/retain]
        (testing "3.1 second supplementary identity work cannot consume cleanup"
          (let [{:keys [failure elapsed-ms direct-supervisor]}
                (run-with-retain
                 profile
                 (fn [handle role]
                   (if (= "supervisor" role)
                     (do (Thread/sleep 3100)
                         (original-retain handle role))
                     (original-retain handle role))))]
            (is (re-find #"Guidance preflight timed out"
                         (ex-message failure)))
            (is (< elapsed-ms 3600.0))
            (is direct-supervisor)
            (is (not (identity/live? direct-supervisor)))
            (is (zero? (worker-count "guidance-admission-worker")))
            (is (zero? (worker-count "guidance-cleanup-worker")))))
        (testing "unavailable supplementary birth proof still reaps the original"
          (let [{:keys [failure elapsed-ms direct-supervisor]}
                (run-with-retain
                 profile
                 (fn [handle role]
                   (if (= "supervisor" role)
                     (throw (ex-info "injected unavailable start identity" {}))
                     (original-retain handle role))))]
            (is (re-find #"injected unavailable start identity"
                         (ex-message failure)))
            (is (< elapsed-ms 3000.0))
            (is direct-supervisor)
            (is (not (identity/live? direct-supervisor)))
            (is (zero? (worker-count "guidance-admission-worker")))
            (is (zero? (worker-count "guidance-cleanup-worker")))))))))

(ns ct.spools.harnesses.guidance-process-deadline-test
  "Real-process deadline and first-acquisition custody regressions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.guidance-capability-test]
            [ct.spools.harnesses.internal.guidance-authority :as authority]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline]
            [ct.spools.harnesses.internal.guidance-process :as process]
            [ct.spools.harnesses.internal.guidance-process-gate :as gate]
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

(defn- sleep-uninterruptibly! [millis]
  (let [until (+ (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS millis))]
    (loop []
      (let [remaining (- until (System/nanoTime))]
        (when (pos? remaining)
          (try
            (Thread/sleep (max 1 (.toMillis TimeUnit/NANOSECONDS remaining)))
            (catch InterruptedException _ nil))
          (recur))))))

(defn- worker-count [name]
  (->> (.keySet (Thread/getAllStackTraces))
       (filter #(and (.isAlive ^Thread %)
                     (= name (.getName ^Thread %))))
       count))

(defn- workers-retired? [name]
  (loop [remaining 200]
    (if (zero? (worker-count name))
      true
      (if (zero? remaining)
        false
        (do
          (Thread/sleep 10)
          (recur (dec remaining)))))))

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
    (is (workers-retired? "guidance-admission-worker"))))

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
    (is (workers-retired? "guidance-admission-worker"))))

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
    (is (workers-retired? "guidance-admission-worker"))))

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

(deftest delayed-helper-gate-cannot-outlive-owned-roots
  (with-profile
    (fn [{:keys [profile]}]
      (let [original-prepare gate/*prepare!*
            original-retain-direct identity/retain-direct
            original-retain-child! identity/retain-child!
            entered (promise)
            retained (atom [])
            delayed-prepare
            (fn [temporary value]
              (if (str/starts-with? (str (.getFileName temporary))
                                    "helper.ready.tmp-")
                (do
                  (deliver entered true)
                  (sleep-uninterruptibly! 3100)
                  (original-prepare temporary value))
                (original-prepare temporary value)))
            {:keys [failure elapsed-ms]}
            (with-redefs
             [gate/*prepare!* delayed-prepare
              identity/retain-direct
              (fn [& args]
                (let [value (apply original-retain-direct args)]
                  (swap! retained conj value)
                  value))
              identity/retain-child!
              (fn [& args]
                (let [value (apply original-retain-child! args)]
                  (swap! retained conj value)
                  value))]
              (timed-failure
               #(process/run! (assoc profile :effective-environment {})
                              "{\"probe\":true}"
                              (deadline/start)
                              identity)))]
        (is (deref entered 1000 false))
        (is (re-find #"Guidance preflight timed out" (ex-message failure)))
        (is (< elapsed-ms 3200.0))
        (is (seq @retained))
        (is (every? #(not (identity/live? %)) @retained))
        (is (workers-retired? "guidance-admission-worker"))
        (is (zero? (worker-count "guidance-cleanup-worker")))))))

(deftest supervisor-identity-is-bounded-and-direct-process-custody-survives
  (with-profile
    (fn [{:keys [profile]}]
      (let [original-retain identity/retain]
        (testing "3.1 second uninterruptible identity work cannot consume cleanup"
          (let [{:keys [failure elapsed-ms direct-supervisor]}
                (run-with-retain
                 profile
                 (fn [handle role]
                   (if (= "supervisor" role)
                     (do (sleep-uninterruptibly! 3100)
                         (original-retain handle role))
                     (original-retain handle role))))]
            (is (re-find #"Guidance preflight timed out"
                         (ex-message failure)))
            (is (< elapsed-ms 3200.0))
            (is (some #(= "verification-worker-retirement"
                          (:phase (ex-data %)))
                      (.getSuppressed ^Throwable failure)))
            (is direct-supervisor)
            (is (not (identity/live? direct-supervisor)))
            (is (workers-retired? "guidance-admission-worker"))
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
            (is (workers-retired? "guidance-admission-worker"))
            (is (zero? (worker-count "guidance-cleanup-worker")))))))))

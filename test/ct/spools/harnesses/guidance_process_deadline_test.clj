(ns ct.spools.harnesses.guidance-process-deadline-test
  "Real-process deadline and first-acquisition custody regressions."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.guidance-capability-test]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline]
            [ct.spools.harnesses.internal.guidance-process :as process]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity]))

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
                      (fn [handle role]
                        (let [retained (original-direct handle role)]
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

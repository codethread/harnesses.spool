(ns ct.spools.harnesses.guidance-process-gate-test
  "Deadline fencing for native process gate publication."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-process-gate :as gate])
  (:import [java.nio.file Files]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- short-budget [on-revoked]
  (let [now (System/nanoTime)]
    {:started-at now
     :work-deadline (+ now (.toNanos TimeUnit/MILLISECONDS 80))
     :deadline (+ now (.toNanos TimeUnit/MILLISECONDS 300))
     :on-revoked on-revoked}))

(defn- await-uninterruptibly! [^CountDownLatch latch]
  (loop []
    (when-not (try
                (.await latch)
                true
                (catch InterruptedException _ false))
      (recur))))

(deftest delayed-preparation-cannot-publish-after-revocation
  (doseq [phase ["supervisor-release"
                 "helper-start"
                 "helper-execution-release"]]
    (testing phase
      (let [directory (Files/createTempDirectory
                       "guidance-gate-test-"
                       (make-array java.nio.file.attribute.FileAttribute 0))
            path (.resolve directory (str phase ".ready"))
            entered (CountDownLatch. 1)
            release (CountDownLatch. 1)
            cleanup-calls (atom 0)
            original gate/*prepare!*
            delayed (fn [temporary value]
                      (.countDown entered)
                      (await-uninterruptibly! release)
                      (original temporary value))]
        (with-redefs [gate/*prepare!* delayed]
          (let [result (future
                         (try
                           (gate/publish!
                            (short-budget #(swap! cleanup-calls inc))
                            path "token" phase)
                           nil
                           (catch Throwable error error)))]
            (is (.await entered 1 TimeUnit/SECONDS))
            (let [error (deref result 1000 ::timeout)]
              (is (instance? Throwable error))
              (is (= 1 @cleanup-calls))
              (is (false? (Files/exists
                           path (make-array java.nio.file.LinkOption 0)))))
            (.countDown release)
            (Thread/sleep 50)))
        (is (false? (Files/exists
                     path (make-array java.nio.file.LinkOption 0))))
        (with-open [paths (Files/list directory)]
          (is (empty? (iterator-seq (.iterator paths)))))
        (Files/delete directory)))))

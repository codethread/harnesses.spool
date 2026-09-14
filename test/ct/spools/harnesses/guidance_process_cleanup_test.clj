(ns ct.spools.harnesses.guidance-process-cleanup-test
  "Real-process partial-correlation and proven-descendant cleanup regressions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-process-cleanup :as cleanup]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity]
            [ct.spools.harnesses.internal.guidance-process-scan :as scan])
  (:import [java.io BufferedReader InputStreamReader]
           [java.lang ProcessHandle]
           [java.util.concurrent Executors TimeUnit]))

(defn- start-sleep! []
  (.start (ProcessBuilder. ^java.util.List ["/bin/sleep" "30"])))

(defn- stop! [process]
  (when (and process (.isAlive process))
    (.destroyForcibly process))
  (when process
    (.waitFor process 2 TimeUnit/SECONDS)))

(defn- stop-handle! [handle]
  (when (and handle (.isAlive handle))
    (.destroyForcibly handle))
  (when handle
    (.get (.onExit handle) 2 TimeUnit/SECONDS)))

(defn- start-process-group! []
  (let [script (str "import os, signal, subprocess, time\n"
                    "os.setsid()\n"
                    "signal.signal(signal.SIGCHLD, signal.SIG_IGN)\n"
                    "children = [subprocess.Popen(['/bin/sleep', '30']) "
                    "for _ in range(2)]\n"
                    "print(os.getpgrp(), *(child.pid for child in children), "
                    "flush=True)\n"
                    "time.sleep(30)\n")
        anchor (.start (ProcessBuilder. ^java.util.List
                        ["/usr/bin/python3" "-c" script]))
        output (BufferedReader. (InputStreamReader. (.getInputStream anchor)))
        line (.readLine output)
        values (when line
                 (mapv parse-long (str/split (str/trim line) #"\s+")))]
    (when-not (and (= 3 (count values)) (every? pos-int? values))
      (stop! anchor)
      (throw (ex-info "Process-group fixture failed to publish its PIDs"
                      {:values values})))
    (let [[pgid & child-pids] values]
      (when-not (= pgid (.pid anchor))
        (stop! anchor)
        (throw (ex-info "Process-group fixture did not establish a session"
                        {:pid (.pid anchor) :pgid pgid})))
      {:anchor anchor
       :output output
       :pgid pgid
       :children (mapv #(or (.orElse (ProcessHandle/of %) nil)
                            (throw (ex-info "Process-group child disappeared"
                                            {:pid %})))
                       child-pids)})))

(defn- failure [operation]
  (try
    (operation)
    nil
    (catch Throwable error
      error)))

(defn- with-interleave [hook operation]
  (with-redefs-fn
    {(ns-resolve 'ct.spools.harnesses.internal.guidance-process-identity
                 'interleave!) hook}
    operation))

(defn- remaining [deadline]
  (- deadline (System/nanoTime)))

(deftest partial-correlation-preserves-and-cleans-each-surviving-original-birth
  (doseq [exiting-index [0 1]]
    (testing (str "member " exiting-index " exits first")
      (let [{anchor-process :anchor output :output
             member-processes :children pgid :pgid}
            (start-process-group!)
            sentinel (start-sleep!)
            executor (Executors/newSingleThreadExecutor)
            anchor (identity/retain (.toHandle anchor-process) "anchor")
            rows (into [{:pid pgid :pgid pgid}]
                       (map (fn [process]
                              {:pid (.pid process) :pgid pgid}))
                       member-processes)
            scan-count (atom 0)
            barrier-crossed? (atom false)
            joins (atom [])
            selected (nth member-processes exiting-index)
            survivor (nth member-processes (- 1 exiting-index))
            original-join identity/join!
            deadline (+ (System/nanoTime)
                        (.toNanos TimeUnit/MILLISECONDS 400))]
        (try
          (let [error
                (with-redefs
                 [scan/scan! (fn [& _]
                               (swap! scan-count inc)
                               rows)
                  identity/join!
                  (fn [retained shared-deadline remaining-nanos]
                    (swap! joins conj
                           {:deadline shared-deadline
                            :pid (:pid retained)
                            :role (:role retained)})
                    (original-join retained shared-deadline remaining-nanos))]
                  (with-interleave
                    (fn [phase retained]
                      (when (and (= :before-member-correlation phase)
                                 (= (.pid selected) (:pid retained))
                                 (compare-and-set! barrier-crossed? false true))
                        (is (= 2 @scan-count))
                        (.destroyForcibly selected)
                        (is (.get (.onExit selected)
                                  1 TimeUnit/SECONDS))))
                    #(failure
                      (fn []
                        (cleanup/cleanup-owned!
                         (atom {:anchor anchor :pgid pgid})
                         executor [output] nil nil nil nil deadline
                         remaining)))))]
            (is (re-find #"identity disappeared" (ex-message error)))
            (is (true? @barrier-crossed?))
            (is (= 2 @scan-count))
            (is (not (.isAlive anchor-process)))
            (is (every? #(not (.isAlive %)) member-processes))
            (is (.isAlive sentinel))
            (is (= #{deadline} (set (map :deadline @joins))))
            (is (= "owned-group-member"
                   (some #(when (= (.pid survivor) (:pid %)) (:role %))
                         @joins))))
          (finally
            (stop! anchor-process)
            (doseq [process member-processes]
              (stop-handle! process))
            (stop! sentinel)))))))

(deftest confirming-scanner-failure-keeps-only-earlier-proven-authority
  (let [anchor-process (start-sleep!)
        sibling-process (start-sleep!)
        unconfirmed-process (start-sleep!)
        sentinel (start-sleep!)
        executor (Executors/newSingleThreadExecutor)
        anchor (identity/retain (.toHandle anchor-process) "anchor")
        sibling (identity/retain (.toHandle sibling-process) "proven-sibling")
        pgid (:pid anchor)
        rows [{:pid pgid :pgid pgid}
              {:pid (.pid unconfirmed-process) :pgid pgid}]
        scan-count (atom 0)
        error
        (try
          (with-redefs [scan/scan!
                        (fn [& _]
                          (if (= 1 (swap! scan-count inc))
                            rows
                            (throw (ex-info "confirming scanner failed" {}))))]
            (failure
             #(cleanup/cleanup-owned!
               (atom {:anchor anchor
                      :pgid pgid
                      :proven-children [sibling]})
               executor [] nil nil nil nil
               (+ (System/nanoTime) 400000000) remaining)))
          (finally
            (.shutdownNow executor)))]
    (try
      (is (= "confirming scanner failed" (ex-message error)))
      (is (= 2 @scan-count))
      (is (not (.isAlive anchor-process)))
      (is (not (.isAlive sibling-process)))
      (is (.isAlive unconfirmed-process))
      (is (.isAlive sentinel))
      (finally
        (doseq [process [anchor-process sibling-process unconfirmed-process
                         sentinel]]
          (stop! process))))))

(deftest parent-proven-descendants-are-signalled-before-shared-deadline-joins
  (let [node (capability/resolve-executable "node" (System/getenv))
        parent
        (.start
         (ProcessBuilder.
          ^java.util.List
          [node "-e"
           (str "const {spawn}=require('node:child_process');"
                "const child=spawn('/bin/sleep',['30']);"
                "console.log(child.pid);"
                "setInterval(()=>{},60000);")]))
        output (BufferedReader. (InputStreamReader. (.getInputStream parent)))
        child-pid (parse-long (.readLine output))
        child (.orElse (ProcessHandle/of child-pid) nil)
        sentinel (start-sleep!)
        executor (Executors/newSingleThreadExecutor)
        parent-identity (identity/retain (.toHandle parent) "proven-parent")
        deadline (+ (System/nanoTime) 1000000000)
        join-deadlines (atom [])
        original-join identity/join!]
    (try
      (let [cleaned
            (with-redefs [identity/join!
                          (fn [retained shared-deadline remaining-nanos]
                            (swap! join-deadlines conj shared-deadline)
                            (original-join retained shared-deadline
                                           remaining-nanos))]
              (cleanup/cleanup-owned!
               (atom {:proven-children [parent-identity]})
               executor [output] nil nil nil nil deadline remaining))]
        (is (= #{(.pid parent) child-pid} (set cleaned)))
        (is (not (.isAlive parent)))
        (is (not (.isAlive child)))
        (is (.isAlive sentinel))
        (is (= #{deadline} (set @join-deadlines))))
      (finally
        (stop! parent)
        (when (and child (.isAlive child))
          (.destroyForcibly child))
        (when child
          (.get (.onExit child) 2 TimeUnit/SECONDS))
        (stop! sentinel)))))

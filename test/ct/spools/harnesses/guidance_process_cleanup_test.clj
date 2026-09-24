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

(defn- await-exit! [^ProcessHandle handle deadline]
  ;; ProcessHandle.onExit polls non-child processes at 300 ms on this JDK.
  ;; Wait for actual death, not that delayed notification, inside the existing
  ;; shared cleanup budget. Retain the original handle throughout.
  (loop []
    (if-not (.isAlive handle)
      handle
      (do
        (when-not (pos? (remaining deadline))
          (throw (ex-info "Fixture member did not exit before cleanup deadline"
                          {:pid (.pid handle)})))
        (Thread/yield)
        (recur)))))

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
                        (is (await-exit! selected deadline))))
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

(deftest anchor-loss-before-promotion-never-adopts-a-group-replacement
  (let [{anchor-process :anchor output :output
         member-processes :children pgid :pgid}
        (start-process-group!)
        [proven-process replacement-process] member-processes
        sentinel (start-sleep!)
        executor (Executors/newSingleThreadExecutor)
        anchor (identity/retain (.toHandle anchor-process) "anchor")
        proven (identity/retain proven-process "independently-proven")
        rows [{:pid pgid :pgid pgid}
              {:pid (.pid replacement-process) :pgid pgid}]
        promoted (atom [])
        error
        (try
          (with-redefs [scan/scan! (fn [& _] rows)]
            (with-interleave
              (fn [phase retained]
                (when (= :before-member-promotion phase)
                  (.destroyForcibly anchor-process)
                  (.get (.onExit anchor-process) 1 TimeUnit/SECONDS))
                (when (= :before-signal phase)
                  (swap! promoted conj (:pid retained))))
              #(failure
                (fn []
                  (cleanup/cleanup-owned!
                   (atom {:anchor anchor :pgid pgid
                          :proven-children [proven]})
                   executor [output] nil nil nil nil
                   (+ (System/nanoTime) 1000000000) remaining)))))
          (finally
            (.shutdownNow executor)))]
    (try
      (is (re-find #"anchor changed" (ex-message error)))
      (is (not (.isAlive proven-process)))
      (is (.isAlive replacement-process))
      (is (.isAlive sentinel))
      (is (not-any? #{(.pid replacement-process)} @promoted))
      (finally
        (stop! anchor-process)
        (doseq [process member-processes]
          (stop-handle! process))
        (stop! sentinel)))))

(deftest unavailable-sibling-birth-preserves-other-child-in-both-orders
  (doseq [unavailable-index [0 1]]
    (testing (str "unavailable child at index " unavailable-index)
      (let [{parent-process :anchor output :output
             child-processes :children}
            (start-process-group!)
            unavailable (nth child-processes unavailable-index)
            proven (nth child-processes (- 1 unavailable-index))
            sentinel (start-sleep!)
            executor (Executors/newSingleThreadExecutor)
            parent (identity/retain (.toHandle parent-process) "parent")
            original-retain identity/retain
            error
            (try
              (with-redefs
               [identity/retain
                (fn [handle role]
                  (if (= (.pid ^ProcessHandle handle) (.pid unavailable))
                    (throw (ex-info
                            "Guidance process start identity is unavailable"
                            {:pid (.pid unavailable)}))
                    (original-retain handle role)))]
                (failure
                 #(cleanup/cleanup-owned!
                   (atom {:supervisor parent}) executor [output]
                   nil nil nil nil
                   (+ (System/nanoTime) 1000000000) remaining)))
              (finally
                (.shutdownNow executor)))]
        (try
          (is (re-find #"start identity is unavailable" (ex-message error)))
          (is (not (.isAlive parent-process)))
          (is (not (.isAlive proven)))
          (is (.isAlive unavailable))
          (is (.isAlive sentinel))
          (finally
            (stop! parent-process)
            (doseq [process child-processes]
              (stop-handle! process))
            (stop! sentinel)))))))

(deftest parent-loss-after-one-child-proof-preserves-that-child
  (let [{parent-process :anchor output :output
         child-processes :children}
        (start-process-group!)
        sentinel (start-sleep!)
        executor (Executors/newSingleThreadExecutor)
        parent (identity/retain (.toHandle parent-process) "parent")
        promoted-pid (atom nil)
        error
        (try
          (with-interleave
            (fn [phase retained]
              (when (and (= :after-child-promotion phase)
                         (compare-and-set! promoted-pid nil (:pid retained)))
                (.destroyForcibly parent-process)
                (.get (.onExit parent-process) 1 TimeUnit/SECONDS)))
            #(failure
              (fn []
                (cleanup/cleanup-owned!
                 (atom {:supervisor parent}) executor [output]
                 nil nil nil nil
                 (+ (System/nanoTime) 1000000000) remaining))))
          (finally
            (.shutdownNow executor)))
        promoted (some #(when (= @promoted-pid (.pid %)) %) child-processes)
        unproven (remove #(= @promoted-pid (.pid %)) child-processes)]
    (try
      (is (re-find #"parent changed" (ex-message error)))
      (is (some? promoted))
      (is (not (.isAlive promoted)))
      (is (every? #(.isAlive %) unproven))
      (is (.isAlive sentinel))
      (finally
        (stop! parent-process)
        (doseq [process child-processes]
          (stop-handle! process))
        (stop! sentinel)))))

(deftest descendant-enumeration-failure-preserves-earlier-proven-custody
  (let [root-birth {:pid 8101 :started-at :root-birth}
        signals (atom [])
        child {:pid 8102
               :started-at :child-birth
               :alive? (constantly true)
               :current-start (constantly :child-birth)
               :parent-birth (constantly root-birth)
               :visit-children! (fn [_])
               :destroy! #(do (swap! signals conj 8102) true)}
        root (merge root-birth
                    {:alive? (constantly true)
                     :current-start (constantly :root-birth)
                     :visit-children!
                     (fn [visit!]
                       (visit! child)
                       (throw (ex-info "later descendant enumeration failed" {})))
                     :destroy! #(do (swap! signals conj 8101) true)})
        error
        (failure
         #(with-redefs [identity/join! (fn [retained _ _] retained)]
            (cleanup/cleanup-owned!
             (atom {:proven-children [root]}) nil [] nil nil nil nil
             (+ (System/nanoTime) 1000000000) remaining)))]
    (is (re-find #"later descendant enumeration failed" (ex-message error)))
    (is (= #{8101 8102} (set @signals)))))

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

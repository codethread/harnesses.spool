(ns ct.spools.harnesses.internal.guidance-deadline
  "One monotonic work and cleanup budget for native guidance admission."
  (:require [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.util.concurrent Callable ExecutionException Executors Future
            ThreadFactory TimeUnit TimeoutException]))

(def ^:private timeout-millis 3000)
(def ^:private cleanup-millis 400)

(defn- daemon-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "guidance-admission-worker")
        (.setDaemon true)))))

(defn start
  "Create one 3000 ms total budget with its final 400 ms reserved for cleanup."
  []
  (let [started-at (System/nanoTime)
        deadline (+ started-at
                    (.toNanos TimeUnit/MILLISECONDS timeout-millis))]
    {:started-at started-at
     :deadline deadline
     :work-deadline (- deadline
                       (.toNanos TimeUnit/MILLISECONDS cleanup-millis))}))

(defn deadline
  "Return the total monotonic cleanup deadline."
  [budget]
  (:deadline budget))

(defn work-deadline
  "Return the monotonic deadline before the cleanup reserve begins."
  [budget]
  (:work-deadline budget))

(defn remaining-nanos
  "Return nanoseconds remaining until a selected monotonic deadline."
  [deadline]
  (- deadline (System/nanoTime)))

(defn timed-out!
  "Fail one named native guidance phase with the fixed total timeout."
  [phase]
  (fail! "Guidance preflight timed out"
         {:timeout-millis timeout-millis :phase phase}))

(defn check!
  "Fail when work has reached the cleanup reserve."
  [budget phase]
  (when-not (pos? (remaining-nanos (work-deadline budget)))
    (timed-out! phase)))

(defn- await! [^Future future budget phase]
  (let [remaining (remaining-nanos (work-deadline budget))]
    (when-not (pos? remaining)
      (.cancel future true)
      (timed-out! phase))
    (try
      (.get future remaining TimeUnit/NANOSECONDS)
      (catch TimeoutException _
        (.cancel future true)
        (timed-out! phase))
      (catch ExecutionException error
        (throw (.getCause error)))
      (catch InterruptedException _
        (.cancel future true)
        (.interrupt (Thread/currentThread))
        (fail! "Guidance preflight was interrupted" {:phase phase})))))

(defn bounded!
  "Run potentially blocking read-only work in one cancellable owned worker.

  Work must finish before the cleanup reserve. Timeout cancels the worker and
  uses that reserve to join it before control can proceed to a launch or
  admission phase."
  [budget phase operation]
  (check! budget phase)
  (let [executor (Executors/newSingleThreadExecutor (daemon-thread-factory))
        future (.submit executor ^Callable operation)]
    (try
      (let [result (await! future budget phase)]
        (check! budget phase)
        result)
      (finally
        (.cancel future true)
        (.shutdownNow executor)
        (let [remaining (remaining-nanos (deadline budget))]
          (when-not (and (pos? remaining)
                         (.awaitTermination executor remaining
                                            TimeUnit/NANOSECONDS))
            (timed-out! "verification-worker-retirement")))))))

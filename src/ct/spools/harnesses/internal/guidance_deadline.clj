(ns ct.spools.harnesses.internal.guidance-deadline
  "One monotonic work and cleanup budget for native guidance admission."
  (:require [ct.spools.harnesses.internal.guidance-authority :as authority]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.util.concurrent Callable ExecutionException Executors Future
            ThreadFactory TimeUnit TimeoutException]))

(def ^:private timeout-millis 3000)
(def ^:private cleanup-millis 900)

(defn- daemon-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "guidance-admission-worker")
        (.setDaemon true)))))

(defn start
  "Create one 3000 ms total budget with its final 900 ms reserved for cleanup."
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

(defn await-future!
  "Await an owned future until an absolute monotonic deadline."
  [^Future future monotonic-deadline phase]
  (let [remaining (remaining-nanos monotonic-deadline)]
    (when-not (pos? remaining)
      (timed-out! phase))
    (try
      (.get future remaining TimeUnit/NANOSECONDS)
      (catch TimeoutException _
        (timed-out! phase))
      (catch ExecutionException error
        (throw (.getCause error)))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (fail! "Guidance preflight was interrupted" {:phase phase})))))

(defn check!
  "Fail when work has reached the selected worker deadline."
  [budget phase]
  (when-not (pos? (remaining-nanos (work-deadline budget)))
    (timed-out! phase)))

(defn- await! [^Future future budget phase]
  (let [remaining (remaining-nanos (work-deadline budget))]
    (when-not (pos? remaining)
      (timed-out! phase))
    (try
      (.get future remaining TimeUnit/NANOSECONDS)
      (catch TimeoutException _
        (timed-out! phase))
      (catch ExecutionException error
        (throw (.getCause error)))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (fail! "Guidance preflight was interrupted" {:phase phase})))))

(defn- retire! [executor budget]
  (.shutdownNow executor)
  (let [remaining (remaining-nanos (deadline budget))]
    (when-not (and (pos? remaining)
                   (.awaitTermination executor remaining
                                      TimeUnit/NANOSECONDS))
      (timed-out! "verification-worker-retirement"))))

(defn owned!
  "Run cancellable work with revocable operation-local authority.

  Failure revokes authority before cancellation. Owned custody cleanup starts
  before worker retirement, so retirement cannot consume the cleanup reserve."
  [budget phase operation]
  (check! budget phase)
  (let [operation-authority (authority/create (work-deadline budget))
        executor (Executors/newSingleThreadExecutor (daemon-thread-factory))
        future (.submit executor ^Callable #(operation operation-authority))
        result (atom nil)
        failure (atom nil)]
    (try
      (try
        (reset! result (await! future budget phase))
        (check! budget phase)
        (catch Throwable error
          (reset! failure error)))
      (finally
        (authority/revoke! operation-authority)
        (.cancel future true)
        (when (and @failure (:on-revoked budget))
          (try
            ((:on-revoked budget))
            (catch Throwable cleanup-error
              (.addSuppressed ^Throwable @failure cleanup-error))))
        (try
          (retire! executor budget)
          (catch Throwable cleanup-error
            (if-let [error @failure]
              (.addSuppressed ^Throwable error cleanup-error)
              (reset! failure cleanup-error))))))
    (if-let [error @failure]
      (throw error)
      @result)))

(defn bounded!
  "Run potentially blocking work in one cancellable owned worker."
  [budget phase operation]
  (owned! budget phase (fn [_] (operation))))

(ns ct.spools.harnesses.internal.execution-state
  "Opened-generation ownership, scheduling, and native guidance deadlines."
  (:require [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-receipts :as guidance-receipts]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.time Instant]
           [java.util UUID]
           [java.util.concurrent Executors ThreadFactory TimeUnit]))

(def state-version
  "Version of the runtime-owned execution state shape."
  4)

(defn- daemon-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "harness-worker")
        (.setDaemon true)))))

(defn new-state
  "Create one execution generation before runtime activation."
  []
  (let [executor (Executors/newCachedThreadPool (daemon-thread-factory))
        scheduler (java.util.concurrent.ScheduledThreadPoolExecutor.
                   1 (daemon-thread-factory))
        open? (atom true)]
    {:generation (str (UUID/randomUUID))
     :open? open?
     :in-flight (atom #{})
     :deferred-recovery (atom nil)
     :reconciliation-failures (atom {})
     :inspection-scheduled? (atom false)
     :executor executor
     :scheduler scheduler
     :close-fn (fn []
                 (reset! open? false)
                 (.shutdownNow executor)
                 (.shutdownNow scheduler)
                 (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                 (.awaitTermination scheduler 1000 TimeUnit/MILLISECONDS))}))

(defn state-holder
  "Return the runtime-owned holder for the active execution generation."
  [rt]
  (runtime/spool-state rt :ct.spools.harnesses.execution/state
                       {:version state-version}
                       #(hash-map :active (atom nil))))

(defn state
  "Return the open execution generation or fail loudly."
  [rt]
  (or @(:active (state-holder rt))
      (fail! "Harness execution resources are not open" {})))

(defn activate-state!
  "Install and return one fresh execution generation."
  [rt]
  (let [active (:active (state-holder rt))
        opened (new-state)
        activated?
        #_{:clj-kondo/ignore [:locking-suspicious-lock]}
        #_{:splint/disable [lint/locking-object]}
        (locking (catalog/publication-lock rt)
          (compare-and-set! active nil opened))]
    (when-not activated?
      ((:close-fn opened))
      (fail! "Harness execution resources are already open" {}))
    opened))

(defn deactivate-state!
  "Atomically retire and return the active execution generation."
  [rt]
  (let [active (:active (state-holder rt))]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (let [opened @active]
        (when-not opened
          (fail! "Harness execution resources are not open" {}))
        #_{:splint/disable [lint/locking-object]}
        (locking (:open? opened)
          (when-not (and @(:open? opened)
                         (compare-and-set! active opened nil))
            (fail! "Harness execution resources are not open" {}))
          (reset! (:open? opened) false))
        opened))))

(defn active-opened?
  "Return whether `opened` is still the runtime's live generation."
  [rt opened]
  (and opened
       @(:open? opened)
       (identical? opened @(:active (state-holder rt)))))

(defn- full-run [rt id]
  (guidance/validation-run
   rt (or (weaver/show rt id) (fail! "Harness run not found" {:id id}))))

(defn- same-attempt? [originating-run current]
  (and (= (:id originating-run) (:id current))
       (= (attr-get originating-run :harness/attempt)
          (attr-get current :harness/attempt))
       (= (life/invocation originating-run) (life/invocation current))))

(defn- eligible-guidance-deadline? [run record]
  (and (= "running" (life/status run))
       (= "interactive" (attr-get run :harness/mode))
       (guidance/native? run)
       (contains? #{"pending" "fetched"} (get record "state"))
       (not (and (= "pi" (attr-get run :harness/harness))
                 (= "fetched" (get record "state"))))))

(defn- deadline-hook! [payload]
  ((requiring-resolve
    'ct.spools.harnesses.execution/guidance-deadline-hook!) payload))

(declare arm-guidance-deadline!)

(defn- schedule-guidance-task! [rt originating-run opened delay-nanos]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (when (active-opened? rt opened)
      #_{:splint/disable [lint/locking-object]}
      (locking (:open? opened)
        (when (active-opened? rt opened)
          (.schedule
           ^java.util.concurrent.ScheduledExecutorService (:scheduler opened)
           ^Runnable #(arm-guidance-deadline! rt originating-run opened)
           (max 1 delay-nanos)
           TimeUnit/NANOSECONDS)
          :scheduled)))))

(defn arm-guidance-deadline!
  "Expire or rearm one attempt under its opened-generation fence."
  [rt originating-run opened]
  (let [generation (:generation opened)]
    (deadline-hook! {:phase :before-publication-lock
                     :run-id (:id originating-run)
                     :generation generation})
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    #_{:splint/disable [lint/locking-object]}
    (locking (catalog/publication-lock rt)
      (when (active-opened? rt opened)
        (let [current (full-run rt (:id originating-run))
              _ (deadline-hook! {:phase :after-reload
                                 :run-id (:id originating-run)
                                 :generation generation})
              record (guidance/current-attempt current)]
          (when (and (same-attempt? originating-run current)
                     (eligible-guidance-deadline? current record))
            (let [now (Instant/now)]
              (if (guidance/deadline-expired? current record now)
                (do (guidance-receipts/expire! rt current) :expired)
                (let [deadline (Instant/parse (get record "deadline-at"))
                      delay-nanos
                      (.toNanos (java.time.Duration/between now deadline))]
                  (schedule-guidance-task! rt originating-run opened
                                           delay-nanos))))))))))

(defn schedule-guidance-deadline!
  "Inspect one native guidance deadline in the selected generation."
  ([rt run] (schedule-guidance-deadline! rt (state rt) run))
  ([rt opened run] (arm-guidance-deadline! rt run opened)))

(defn recover-guidance-deadlines!
  "Rearm eligible durable deadlines for one newly opened generation."
  [rt opened]
  (->> (weaver/list rt)
       (map #(guidance/validation-run rt %))
       (keep #(schedule-guidance-deadline! rt opened %))
       count))

(defn schedule-inspection!
  "Coalesce a custody inspection into the selected open generation."
  ([rt] (schedule-inspection! rt (state rt)))
  ([rt opened]
   #_{:clj-kondo/ignore [:locking-suspicious-lock]}
   #_{:splint/disable [lint/locking-object]}
   (locking (catalog/publication-lock rt)
     (when (active-opened? rt opened)
       (let [{:keys [inspection-scheduled? scheduler]} opened]
         (when (compare-and-set! inspection-scheduled? false true)
           (.schedule
            ^java.util.concurrent.ScheduledExecutorService scheduler
            ^Runnable
            #(do
               (reset! inspection-scheduled? false)
               ((requiring-resolve
                 'ct.spools.harnesses.execution/inspect-owned!) rt opened))
            100 TimeUnit/MILLISECONDS)))))))

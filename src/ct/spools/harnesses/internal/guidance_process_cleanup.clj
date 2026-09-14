(ns ct.spools.harnesses.internal.guidance-process-cleanup
  "Bounded cleanup for independently proven native preflight processes."
  (:require [ct.spools.harnesses.internal.guidance-deadline :as deadline]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity]
            [ct.spools.harnesses.internal.guidance-process-scan :as scan])
  (:import [java.util.concurrent Callable ExecutionException Executors
            ThreadFactory TimeUnit TimeoutException]))

(defn- record-error! [errors error]
  (swap! errors conj error)
  nil)

(defn- attempt! [errors operation]
  (try
    (operation)
    (catch Throwable error
      (record-error! errors error))))

(def ^:private worker-retirement-nanos
  (.toNanos TimeUnit/MILLISECONDS 20))
(def ^:private signal-reserve-nanos
  (.toNanos TimeUnit/MILLISECONDS 40))

(defn- bounded-until! [end phase operation]
  (deadline/bounded!
   {:work-deadline (- end worker-retirement-nanos) :deadline end}
   phase operation))

(defn- attempt-bounded! [errors end phase operation]
  (attempt! errors #(bounded-until! end phase operation)))

(defn- distinct-identities [identities]
  (vals
   (reduce
    (fn [by-birth retained]
      (let [birth [(:pid retained) (:started-at retained)]]
        (if (contains? by-birth birth)
          by-birth
          (assoc by-birth birth retained))))
    (array-map)
    (remove nil? identities))))

(defn- retain-descendants!
  [errors roots deadline remaining-nanos]
  (loop [pending (vec roots)
         retained []
         seen (set (map (juxt :pid :started-at) roots))]
    (if-let [parent (first pending)]
      (if-not (pos? (remaining-nanos deadline))
        (do
          (record-error!
           errors
           (ex-info "Guidance descendant cleanup exhausted its deadline" {}))
          retained)
        (if (identity/live? parent)
          (let [children (atom [])
                {child-errors :errors}
                (identity/retain-children!
                 parent "proven-descendant-for-cleanup"
                 #(swap! children conj %))
                _ (doseq [error child-errors]
                    (record-error! errors error))
                unseen (remove #(contains? seen [(:pid %) (:started-at %)])
                               @children)]
            (recur (into (subvec pending 1) unseen)
                   (into retained unseen)
                   (into seen (map (juxt :pid :started-at)) unseen)))
          (recur (subvec pending 1) retained seen)))
      retained)))

(defn- await-task! [errors future end phase]
  (let [remaining (- (- end worker-retirement-nanos) (System/nanoTime))]
    (if-not (pos? remaining)
      (record-error! errors
                     (ex-info "Guidance cleanup task exhausted its deadline"
                              {:phase phase}))
      (try
        (.get future remaining TimeUnit/NANOSECONDS)
        (catch TimeoutException _
          (.cancel future true)
          (record-error! errors
                         (ex-info "Guidance cleanup task timed out"
                                  {:phase phase})))
        (catch ExecutionException error
          (record-error! errors (.getCause error)))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          (record-error! errors
                         (ex-info "Guidance cleanup task was interrupted"
                                  {:phase phase})))))))

(defn- cleanup-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "guidance-cleanup-worker")
        (.setDaemon true)))))

(defn- run-all! [errors identities end phase operation]
  (when (seq identities)
    (let [executor (Executors/newFixedThreadPool
                    (count identities) (cleanup-thread-factory))
          futures (mapv #(.submit executor ^Callable (fn [] (operation %)))
                        identities)]
      (try
        (doseq [future futures]
          (await-task! errors future end phase))
        (finally
          (doseq [future futures]
            (.cancel future true))
          (.shutdownNow executor)
          (let [remaining (- end (System/nanoTime))]
            (when-not (and (pos? remaining)
                           (.awaitTermination executor remaining
                                              TimeUnit/NANOSECONDS))
              (record-error!
               errors
               (ex-info "Guidance cleanup workers did not terminate"
                        {:phase phase})))))))))

(defn- preserve-correlated!
  [errors proven anchor retained rows pgid]
  (let [{correlated :confirmed correlation-errors :errors}
        (identity/correlate-members! anchor retained rows pgid
                                     #(swap! proven conj %))]
    (doseq [error correlation-errors]
      (record-error! errors error))
    correlated))

(defn cleanup-owned!
  "Retire every independently proven process under one shared deadline.

  Discovery ends before the signal reserve. Streams and workers retire even
  when discovery fails. Every safe signal is submitted before bounded joins,
  and all failures remain attached in deterministic phase order."
  [ownership executor streams profile process-environment root scanner end
   remaining-nanos]
  (let [{:keys [anchor helper supervisor direct-supervisor pgid
                proven-children]} @ownership
        errors (atom [])
        proven (atom (vec proven-children))
        discovery-end (- end signal-reserve-nanos)]
    (when-not pgid
      (doseq [parent [anchor supervisor direct-supervisor]
              :when parent]
        (attempt-bounded!
         errors discovery-end "cleanup-child-discovery"
         #(when (identity/live? parent)
            (let [{child-errors :errors}
                  (identity/retain-children!
                   parent "proven-child-for-cleanup"
                   (fn [child] (swap! proven conj child)))]
              (doseq [error child-errors]
                (record-error! errors error)))))))
    (when pgid
      (if (try
            (boolean
             (bounded-until! discovery-end "cleanup-anchor-liveness"
                             #(identity/live? anchor)))
            (catch Throwable error
              (record-error! errors error)
              false))
        (try
          (let [first-rows
                (scan/scan! profile process-environment root scanner
                            discovery-end remaining-nanos)
                {retained :retained retention-errors :errors}
                (bounded-until!
                 discovery-end "cleanup-member-retention"
                 #(identity/retain-members anchor first-rows pgid))]
            (doseq [error retention-errors]
              (record-error! errors error))
            (let [confirming-rows
                  (scan/scan! profile process-environment root scanner
                              discovery-end remaining-nanos)]
              (bounded-until!
               discovery-end "cleanup-member-correlation"
               #(preserve-correlated! errors proven anchor retained
                                      confirming-rows pgid))))
          (catch Throwable error
            (record-error! errors error)))
        (record-error!
         errors
         (ex-info
          "Guidance ownership anchor identity disappeared before cleanup"
          {:anchor-pid (:pid anchor) :pgid pgid}))))
    (let [roots (distinct-identities
                 (concat @proven
                         [helper anchor supervisor direct-supervisor]))
          descendants
          (try
            (bounded-until!
             discovery-end "cleanup-descendant-retention"
             #(retain-descendants! errors roots discovery-end remaining-nanos))
            (catch Throwable error
              (record-error! errors error)
              []))
          identities (distinct-identities (concat roots descendants))
          signal-end (- end
                        (.toNanos TimeUnit/MILLISECONDS 20))]
      (doseq [stream streams]
        (try (.close stream) (catch Exception _ nil)))
      (when executor
        (.shutdownNow executor))
      (run-all! errors identities signal-end "cleanup-signal" identity/signal!)
      (run-all! errors identities end "cleanup-join"
                #(identity/join! % end remaining-nanos))
      (when executor
        (let [remaining (remaining-nanos end)]
          (when-not (and (pos? remaining)
                         (.awaitTermination executor remaining
                                            TimeUnit/NANOSECONDS))
            (record-error!
             errors (ex-info "Guidance preflight I/O workers did not terminate"
                             {})))))
      (when-let [error (first @errors)]
        (doseq [suppressed (rest @errors)]
          (.addSuppressed ^Throwable error ^Throwable suppressed))
        (throw error))
      (mapv :pid identities))))

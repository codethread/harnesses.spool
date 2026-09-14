(ns ct.spools.harnesses.internal.guidance-process-cleanup
  "Bounded cleanup for independently proven native preflight processes."
  (:require [ct.spools.harnesses.internal.guidance-process-identity :as identity]
            [ct.spools.harnesses.internal.guidance-process-scan :as scan])
  (:import [java.util.concurrent TimeUnit]))

(defn- record-error! [errors error]
  (swap! errors conj error)
  nil)

(defn- attempt! [errors operation]
  (try
    (operation)
    (catch Throwable error
      (record-error! errors error))))

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
          (let [children
                (try
                  (identity/retain-children
                   parent "proven-descendant-for-cleanup")
                  (catch Throwable error
                    (record-error! errors error)
                    []))
                unseen (remove #(contains? seen [(:pid %) (:started-at %)])
                               children)]
            (recur (into (subvec pending 1) unseen)
                   (into retained unseen)
                   (into seen (map (juxt :pid :started-at)) unseen)))
          (recur (subvec pending 1) retained seen)))
      retained)))

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

  Scanner rows remain unconfirmed discovery. Each birth gains cleanup authority
  only after independent confirmation or original-parent provenance. All safe
  signals happen before bounded joins, and every cleanup failure is retained."
  [ownership executor streams profile process-environment root scanner deadline
   remaining-nanos]
  (let [{:keys [anchor helper supervisor pgid proven-children]} @ownership
        errors (atom [])
        proven (atom (vec proven-children))]
    (when-not pgid
      (doseq [parent [anchor supervisor]
              :when (identity/live? parent)]
        (attempt!
         errors #(swap! proven into
                        (identity/retain-children
                         parent "proven-child-for-cleanup")))))
    (when pgid
      (if (identity/live? anchor)
        (try
          (let [first-rows
                (scan/scan! profile process-environment root scanner
                            deadline remaining-nanos)
                {retained :retained retention-errors :errors}
                (identity/retain-members anchor first-rows pgid)]
            (doseq [error retention-errors]
              (record-error! errors error))
            (let [confirming-rows
                  (scan/scan! profile process-environment root scanner
                              deadline remaining-nanos)]
              (preserve-correlated! errors proven anchor retained
                                    confirming-rows pgid)))
          (catch Throwable error
            (record-error! errors error)))
        (record-error!
         errors
         (ex-info "Guidance ownership anchor identity disappeared before cleanup"
                  {:anchor-pid (:pid anchor) :pgid pgid}))))
    (let [roots (distinct-identities
                 (concat @proven [helper anchor supervisor]))
          descendants (retain-descendants! errors roots deadline
                                           remaining-nanos)
          identities (distinct-identities (concat roots descendants))]
      (doseq [retained identities]
        (attempt! errors #(identity/signal! retained)))
      (doseq [stream streams]
        (try (.close stream) (catch Exception _ nil)))
      (.shutdownNow executor)
      (doseq [retained identities]
        (attempt! errors #(identity/join! retained deadline remaining-nanos)))
      (let [remaining (remaining-nanos deadline)]
        (when-not (and (pos? remaining)
                       (.awaitTermination executor remaining
                                          TimeUnit/NANOSECONDS))
          (record-error!
           errors (ex-info "Guidance preflight I/O workers did not terminate"
                           {}))))
      (when-let [error (first @errors)]
        (doseq [suppressed (rest @errors)]
          (.addSuppressed ^Throwable error ^Throwable suppressed))
        (throw error))
      (mapv :pid identities))))

(ns ct.spools.harnesses.internal.publication
  "Publication completion and retained interruption evidence."
  (:require [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(def ^:dynamic *enrich*
  "Assignment's final enrichment, invoked under the publication fence."
  nil)

(defn check-interrupted!
  "Refuse further publication after cooperative operation cancellation."
  []
  (when (.isInterrupted (Thread/currentThread))
    (throw (InterruptedException. "Agent publication interrupted"))))

(defn incomplete?
  "Return whether retained publication evidence lacks a committed outcome."
  [run]
  (and (= "true" (attr-get run :harness/run))
       (not= "external" (attr-get run :harness/ownership))
       (not= "interrupted" (attr-get run :harness/publication-outcome))
       (not (life/accepted? run))))

(defn interrupt!
  "Retain a terminal interruption without inventing process settlement.

  Call under the publication lock. Only a ready row with no attempt or
  invocation and an incomplete publication proves it never became launchable."
  [rt run reason]
  (if-not (incomplete? run)
    run
    (let [never-launched? (and (= "ready" (life/status run))
                               (nil? (attr-get run :harness/attempt))
                               (nil? (life/invocation run)))
          patch (cond
                  never-launched? (life/stop-patch run reason)
                  (life/terminal? run) {}
                  (= "running" (life/status run)) (life/stop-patch run reason)
                  :else {:harness/status "failed"
                         :harness/substatus "reconciliation"
                         :harness/settlement-gap
                         "Interrupted publication has possible execution custody"})]
      (weaver/update!
       rt (:id run)
       {:state (if never-launched? "closed" (:state run))
        :attributes (merge patch
                           {:harness/publication-outcome "interrupted"
                            :harness/publication-reason reason})}))))

(defn recover!
  "Read one run under the publication fence and settle retained incompleteness."
  [rt id]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (weaver/show rt id)]
      (if (incomplete? run)
        (interrupt! rt run "Publication did not commit before readback")
        run))))

(defn recover-all!
  "Dispose incomplete publications before scheduling a restored world."
  [rt]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (doseq [run (weaver/list rt [:= [:attr "harness/run"] "true"] {})
            :when (incomplete? run)]
      (interrupt! rt run "Publication did not commit before recovery"))))

(defn complete!
  "Atomically commit publication and its accepted predecessor bookkeeping."
  [rt run predecessor-id]
  (when-not (life/assignment-enriched? (weaver/show rt (:id run)))
    (throw (ex-info "Assignment publication lacks final enrichment"
                    {:run-id (:id run)})))
  (check-interrupted!)
  (batch/apply!
   rt
   {:refs (cond-> {:run (:id run)}
            predecessor-id (assoc :predecessor predecessor-id))
    :strands (cond-> [{:ref :run
                       :attributes {:harness/publication-phase "complete"
                                    :harness/publication-outcome "committed"}}]
               predecessor-id
               (conj {:ref :predecessor
                      :attributes {:harness/continued "true"}}))
    :edges [] :burn []})
  (weaver/show rt (:id run)))

(defn fail!
  "Preserve the original failure after recording its incomplete publication.

  Clear interruption only for the terminal evidence write, then restore it.
  A failed evidence write is suppressed on the original failure; readback can
  still recognize the retained incomplete row."
  [rt id error]
  (let [interrupted? (or (Thread/interrupted)
                         (instance? InterruptedException error))]
    (try
      (interrupt! rt (weaver/show rt id) (or (ex-message error)
                                             (.getName (class error))))
      (catch Throwable settlement-error
        (.addSuppressed ^Throwable error settlement-error))
      (finally
        (when interrupted?
          (.interrupt (Thread/currentThread))))))
  (throw error))

(ns ct.spools.harnesses.internal.reconciliation-sweep
  "Durable scheduler state for interactive-run reconciliation."
  (:require [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler])
  (:import [java.time Instant]))

(def ^:private state-key :ct.spools.harnesses.reconciliation/state)
(def ^:private state-version 1)
(def ^:private wake-key "harness/interactive-reconciliation")

(def handler
  "Qualified handler stored in each durable reconciliation wake."
  'ct.spools.harnesses.reconciliation/sweep-wake!)

(defn config-lock
  "Return the runtime-owned lock and active sweep configuration."
  [rt]
  (:sweep-config
   (runtime/spool-state rt state-key {:version state-version}
                        #(hash-map :sweep-config (atom nil)))))

(defn pending
  "Return the one pending durable reconciliation wake, when present."
  [rt]
  (some #(when (= wake-key (:key %)) %) (scheduler/pending rt)))

(defn arm!
  "Schedule the next durable reconciliation wake with its fair-scan cursor."
  [rt interval-ms offset generation]
  (scheduler/schedule!
   rt {:key wake-key
       :wake-at (.plusMillis ^Instant (runtime/now rt) (long interval-ms))
       :handler handler
       :payload {:interval-ms interval-ms
                 :offset offset
                 :generation generation}}))

(defn cancel!
  "Cancel the pending durable reconciliation wake."
  [rt]
  (scheduler/cancel! rt wake-key))

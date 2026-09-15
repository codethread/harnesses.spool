(ns ct.spools.harnesses.internal.guidance-process-retirement
  "Revocable retirement for directly created guidance processes."
  (:require [ct.spools.harnesses.internal.guidance-authority :as authority]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private worker-reserve
  (.toNanos TimeUnit/MILLISECONDS 20))

(defn ^:dynamic ^:private process-live?
  "Test seam for a directly created process liveness probe."
  [process]
  (.isAlive process))

(defn release-process!
  "Probe and retire a directly created process with a final authority fence."
  [process operation-authority retirement-deadline remaining-nanos]
  (when (process-live? process)
    (authority/run! operation-authority "direct-supervisor-retirement-signal"
                    (fn [] (.destroyForcibly process))))
  (let [remaining (remaining-nanos retirement-deadline)]
    (when-not (and (pos? remaining)
                   (.waitFor process remaining TimeUnit/NANOSECONDS))
      (deadline/timed-out! "supervisor-retirement"))))

(defn finalize!
  "Signal the original supervisor directly through a revocable fallback."
  [process retirement-deadline remaining-nanos]
  (deadline/owned!
   {:work-deadline (- retirement-deadline worker-reserve)
    :deadline retirement-deadline}
   "direct-supervisor-finalizer"
   #(authority/run! % "direct-supervisor-finalizer-signal"
                    (fn [] (.destroyForcibly process))))
  (let [remaining (remaining-nanos retirement-deadline)]
    (when-not (and (pos? remaining)
                   (.waitFor process remaining TimeUnit/NANOSECONDS))
      (deadline/timed-out! "direct-supervisor-finalizer-retirement"))))

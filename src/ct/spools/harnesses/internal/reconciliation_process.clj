(ns ct.spools.harnesses.internal.reconciliation-process
  "Local process observations for interactive-run reconciliation."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]])
  (:import [java.lang ProcessHandle]
           [java.net InetAddress]
           [java.util Optional]))

(defn- optional-value [^Optional optional]
  (.orElse optional nil))

(defn- host-name []
  (.getHostName (InetAddress/getLocalHost)))

(defn process-identity
  "Return the current non-signalling identity observation for local PID `pid`."
  [pid]
  (require-valid! pos-int? pid "process-identity requires a positive PID")
  (if-let [^ProcessHandle handle (optional-value (ProcessHandle/of (long pid)))]
    (if-let [started-at (some-> handle .info .startInstant optional-value)]
      {:state (if (.isAlive handle) "live" "gone")
       :pid pid
       :started-at (str started-at)}
      {:state "unavailable"
       :pid pid
       :reason "process start instant is unavailable"})
    {:state "gone" :pid pid}))

(defn scoped-host-observation
  "Return the local host observation scope without hiding lookup failure."
  []
  (try
    {:state "available" :host (host-name)}
    (catch Throwable error
      {:state "unavailable" :reason (ex-message error)})))

(defn- recorded-process-observation [run prefix]
  (let [pid (attr-get run (keyword "harness" (str prefix "-pid")))
        expected-start
        (attr-get run (keyword "harness" (str prefix "-started-at")))
        expected-host (attr-get run (keyword "harness" (str prefix "-host")))
        expected-invocation
        (attr-get run (keyword "harness" (str prefix "-invocation")))
        current-invocation (life/invocation run)
        observed-host (scoped-host-observation)]
    (cond
      (or (nil? pid) (str/blank? expected-start) (str/blank? expected-host)
          (str/blank? expected-invocation))
      {:state "missing"}

      (not= expected-invocation current-invocation)
      {:state "unavailable"
       :reason "process identity belongs to another invocation"
       :recorded-invocation expected-invocation
       :current-invocation current-invocation}

      (= "unavailable" (:state observed-host))
      observed-host

      (not= expected-host (:host observed-host))
      {:state "remote"
       :recorded-host expected-host
       :observed-host (:host observed-host)}

      (not (and (integer? pid) (pos? pid)))
      {:state "unavailable" :reason "recorded process PID is invalid"}

      :else
      (try
        (let [observed (process-identity pid)]
          (if (and (= "live" (:state observed))
                   (not= expected-start (:started-at observed)))
            (assoc observed :state "replaced"
                   :expected-started-at expected-start)
            (assoc observed :expected-started-at expected-start)))
        (catch Throwable error
          {:state "unavailable" :reason (ex-message error)})))))

(defn completion-owner-observation
  "Observe the exact completion-owning bin process without signalling it."
  [run]
  (recorded-process-observation run "completion-owner"))

(defn provider-observation
  "Observe the exact provider exec process without signalling it."
  [run]
  (recorded-process-observation run "provider"))

(defn native-observation
  "Return positive local evidence of a process naming the native session.

  A missing match is only `not-observed`, never proof of provider exit. Process
  metadata may be unavailable under host policy; that protects the run as an
  unknown observation."
  [run]
  (let [session-id (attr-get run :harness/session-id)]
    (if (str/blank? session-id)
      {:state "not-observed"}
      (try
        (with-open [processes (ProcessHandle/allProcesses)]
          (let [observable (atom 0)
                matches
                (->> (iterator-seq (.iterator processes))
                     (keep (fn [^ProcessHandle handle]
                             (when-let [arguments
                                        (optional-value (.arguments (.info handle)))]
                               (swap! observable inc)
                               (when (some #{session-id} (seq arguments))
                                 (.pid handle)))))
                     vec)]
            (cond
              (seq matches) {:state "active" :pids matches}
              (pos? @observable) {:state "not-observed"}
              :else {:state "unavailable"
                     :reason "no process arguments are observable"})))
        (catch Throwable error
          {:state "unavailable" :reason (ex-message error)})))))

(defn completion-owner-attributes
  "Return durable start attributes for the current completion-owning bin PID."
  [pid]
  (require-valid! pos-int? pid
                  "Interactive completion owner requires a positive PID")
  (let [fact (process-identity pid)
        host (scoped-host-observation)]
    (when-not (and (= "live" (:state fact))
                   (= "available" (:state host)))
      (fail! "Interactive completion owner is not positively observable"
             {:process fact :host host}))
    {:harness/completion-owner-pid pid
     :harness/completion-owner-started-at (:started-at fact)
     :harness/completion-owner-host (:host host)}))

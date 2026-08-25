(ns ct.spools.harnesses.internal.process-custody
  "Headless process custody for provider-neutral harness runs.

  Mill owns each child and retains its completion fact. This namespace keeps
  the translation between durable run attributes and Mill's explicit-runtime
  process API separate from the harness state machine."
  (:require [clojure.java.io :as io]
            [millstrand.api.process.alpha :as process]
            [millstrand.api.spool.alpha :refer [fail!]]))

(def owner
  "Stable Mill process-custody owner for all headless agent runs."
  :agent-harness/run)

(def ^:private owner-attribute
  (subs (str owner) 1))

(defn process-key
  "Return the owner-scoped idempotency key for one durable run attempt."
  [run-id attempt]
  (str run-id "/attempt-" attempt))

(defn launch!
  "Reserve and launch one headless agent process through Mill custody.

  Equal calls converge on Mill's existing process record. The returned record
  is always addressed by its opaque handle; no PID is used as domain identity."
  [runtime run-id attempt launch-spec]
  (process/launch! runtime owner (process-key run-id attempt) launch-spec))

(defn durable-attributes
  "Return run attributes that persist one custody record and its stable key."
  [prefix run-id attempt record]
  {(str prefix "/process-owner") owner-attribute
   (str prefix "/process-key") (process-key run-id attempt)
   (str prefix "/process-handle") (:handle record)
   (str prefix "/process-phase") (name (:phase record))
   (str prefix "/attempt") attempt})

(defn list-owned
  "List all unacknowledged headless process facts for the owner."
  [runtime]
  (process/list-owned runtime owner))

(defn record-for
  "Return the one process record matching a run's owner, key, and handle.

  A durable `pending` handle is a launch-window marker. In that case the
  returned record is the recovery candidate, and the caller must persist its
  opaque handle before continuing phase reconciliation. Missing facts,
  mismatched owners or handles, attempt conflicts, and non-unique matches fail
  loudly with owner-local context so callers can continue reconciling healthy
  runs."
  [prefix run records]
  (let [attrs (:attributes run)
        run-id (:id run)
        attempt (get attrs (keyword prefix "attempt"))
        expected-owner (get attrs (keyword prefix "process-owner"))
        expected-key (get attrs (keyword prefix "process-key"))
        expected-handle (get attrs (keyword prefix "process-handle"))
        candidates (filter #(and (= owner (:owner %))
                                 (= expected-key (:key %))) records)]
    (when-not (and (integer? attempt) (pos? attempt))
      (fail! "Process custody run has no valid attempt" {:run-id run-id :attempt attempt}))
    (when-not (= expected-key (process-key run-id attempt))
      (fail! "Process custody run has a conflicting attempt key"
             {:run-id run-id :attempt attempt :expected (process-key run-id attempt)
              :actual expected-key}))
    (when-not (= owner-attribute expected-owner)
      (fail! "Process custody run has a conflicting owner"
             {:run-id run-id :expected owner-attribute :actual expected-owner}))
    (when (empty? candidates)
      (fail! "Process custody fact is missing for an active run"
             {:run-id run-id :attempt attempt :key expected-key}))
    (when (next candidates)
      (fail! "Process custody key has multiple retained facts"
             {:run-id run-id :attempt attempt :key expected-key
              :handles (mapv :handle candidates)}))
    (let [record (first candidates)]
      (if (= "pending" expected-handle)
        (do
          (when (= "pending" (:handle record))
            (fail! "Process custody fact has no opaque handle"
                   {:run-id run-id :attempt attempt :key expected-key}))
          record)
        (do
          (when-not (= expected-handle (:handle record))
            (fail! "Process custody handle does not match the durable run"
                   {:run-id run-id :expected expected-handle :actual (:handle record)}))
          record)))))

(defn terminal-observed
  "Read Mill-retained output and project one terminal record for an engine."
  [record]
  (let [{:keys [stdout-ref stderr-ref]} (:output record)]
    {:exit-code (some-> (:exit record) :code)
     :stdout (slurp (io/file stdout-ref))
     :stderr (slurp (io/file stderr-ref))
     :cancellation (:cancellation record)
     :launch-failure (:launch-failure record)}))

(defn acknowledge!
  "Acknowledge a terminal fact after the owning durable update commits."
  [runtime record]
  (when-not (= :terminal (:phase record))
    (fail! "Only terminal process facts may be acknowledged"
           {:handle (:handle record) :phase (:phase record)}))
  (process/acknowledge! runtime owner (:handle record)))

(defn cancel!
  "Request idempotent cancellation of one owned process tree."
  [runtime record]
  (process/cancel! runtime owner (:handle record)))

(defn terminal-error
  "Return a visible error for a cancellation or launch failure fact."
  [{:keys [cancellation launch-failure]}]
  (or (some-> cancellation :reason)
      (some-> launch-failure :message)))

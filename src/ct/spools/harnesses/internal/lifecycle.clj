(ns ct.spools.harnesses.internal.lifecycle
  "Pure lifecycle projections for durable harness runs.

  Public run state is a `status` plus a `substatus`. `status` answers what the
  run is; `substatus` answers why it got there. Nothing here touches Weaver, so
  every rule below is directly testable against plain strand maps."
  (:require [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [attr-get fail!]])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(def statuses
  "Supported public harness execution statuses."
  #{"ready" "running" "stopped" "failed"})

(def substatuses
  "Supported public harness execution substatuses.

  `pending` is only for a ready run that has not launched. A running run has
  no substatus unless a stop is in flight (`requested`). Failed runs use an
  actionable class: `launch`, `execution`, or `reconciliation`."
  #{"pending" "completed" "requested" "launch" "execution" "reconciliation"})

(defn now
  "Return an ISO-8601 timestamp for durable lifecycle evidence."
  []
  (str (Instant/now)))

(defn status
  "Return the authoritative public status of `run`."
  [run]
  (attr-get run :harness/status))

(defn substatus
  "Return the authoritative public substatus of `run`."
  [run]
  (attr-get run :harness/substatus))

(defn settled?
  "Return true only when the run carries positive settlement evidence."
  [run]
  (= "true" (attr-get run :harness/settled)))

(defn published?
  "Return true once identity, target, and request binding are all durable.

  Only published runs are eligible for scheduling, so a run committed mid
  publication can never be launched or reconciled by a concurrent worker."
  [run]
  (= "true" (attr-get run :harness/published)))

(defn active?
  "Return true when a run is ready to start or currently executing."
  [run]
  (contains? #{"ready" "running"} (status run)))

(defn terminal?
  "Return true when a run has a terminal public status.

  Terminal failure is distinct from positive process settlement."
  [run]
  (contains? #{"stopped" "failed"} (status run)))

(defn reserving?
  "Return true when a run still holds its session or target reservation.

  Unsettled terminal rows keep the reservation: a failed or cancelled run
  whose process has not been proven gone must not free the session for a
  second writer."
  [run]
  (or (active? run)
      (and (terminal? run) (not (settled? run)))))

(defn stop-requested?
  "Return true when durable stop intent exists for `run`."
  [run]
  (some? (attr-get run :harness/stop-requested-at)))

(defn session-usable?
  "Return true only for provider-verified native session evidence."
  [run]
  (= "true" (attr-get run :harness/session-usable)))

(defn logical-id
  "Return the run's stable logical identity across its resume lineage."
  [run]
  (or (attr-get run :harness/logical-id) (:id run)))

(defn invocation
  "Return the fencing token for the run's current execution attempt."
  [run]
  (attr-get run :harness/invocation))

(defn- cancellation-stop
  "Return the keywordized Mill stop class from a cancellation fact, or nil."
  [cancellation]
  (when (map? cancellation)
    (let [stop (or (:stop cancellation) (get cancellation "stop"))]
      (cond
        (#{:graceful "graceful"} stop) :graceful
        (#{:forced "forced"} stop) :forced
        (#{:uncertain "uncertain"} stop) :uncertain
        :else nil))))

(defn settlement-evidence
  "Project positive settlement evidence from one terminal custody observation.

  An observed exit or a launch failure proves the owned process is gone when
  no cancellation class governs the record. A graceful Mill cancellation
  (tree gone after TERM, no KILL escalation) is also settlement. Forced or
  uncertain cancellation proves only that Mill asked its client to stop, even
  when it retains an observed exit from the leader."
  [{:keys [exit-code cancellation launch-failure]}]
  (cond
    ;; A cancellation may retain the leader's observed exit. The exit is
    ;; provider evidence, but the cancellation class remains authoritative for
    ;; custody settlement.
    (some? cancellation)
    (case (cancellation-stop cancellation)
      :graceful {:settled true :settlement "graceful-cancellation"}
      :forced {:settled false
               :settlement "forced-cancellation"
               :gap "Mill escalated to KILL; shared-backend disposal is unproven"}
      :uncertain {:settled false
                  :settlement "uncertain-cancellation"
                  :gap "Mill could not confirm the process tree is gone"}
      {:settled false
       :settlement "cancellation-unproven"
       :gap "Mill retained a cancellation with no stop class; backend disposal is unproven"})

    (some? exit-code)
    {:settled true :settlement "process-exit"}

    (some? launch-failure)
    {:settled true :settlement "launch-failure"}

    :else
    {:settled false :settlement "no-terminal-evidence"}))

(defn failure-class
  "Return the failed-run substatus for one outcome and its evidence."
  [run evidence]
  (or (:failure-class evidence)
      (cond
        (or (nil? (invocation run))
            (= "launch-failure" (:settlement evidence)))
        "launch"

        :else "execution")))

(defn terminal-patch
  "Return the durable state patch for one fenced terminal outcome.

  `outcome-status` is the provider's `:done` or `:failed`. `evidence` comes
  from `settlement-evidence`.

  A normal completion that races an in-flight stop stays `stopped/completed`.
  A confirmed cancellation (stop requested, provider did not complete) becomes
  `stopped/requested`. An earlier failure stays `failed` even when a later
  custody fact settles the process."
  [run outcome-status evidence session-usable?]
  (let [done? (= :done outcome-status)
        cancelled? (and (stop-requested? run)
                        (not done?)
                        (or (:cancelled? evidence)
                            (= "graceful-cancellation" (:settlement evidence))
                            (= "forced-cancellation" (:settlement evidence))
                            (= "uncertain-cancellation" (:settlement evidence))
                            (= "cancellation-unproven" (:settlement evidence))))]
    (merge
     (cond
       cancelled?
       {:harness/status "stopped"
        :harness/substatus "requested"}

       done?
       {:harness/status "stopped"
        :harness/substatus "completed"}

       :else
       {:harness/status "failed"
        :harness/substatus (failure-class run evidence)})
     {:harness/settled (if (:settled evidence) "true" "false")
      :harness/settlement (:settlement evidence)
      :harness/session-usable (if session-usable? "true" "false")}
     (when-let [gap (:gap evidence)]
       {:harness/settlement-gap gap}))))

(defn stop-patch
  "Return the durable patch recording idempotent stop intent for `run`.

  A run that has never launched settles immediately, because there is no
  process and therefore nothing left to prove. A running run keeps its
  `running` status and gains `requested` as the honest in-flight reason:
  only observed settlement may move it."
  [run reason]
  (let [reason (if (str/blank? reason) "stop requested" reason)]
    (cond
      (or (terminal? run) (stop-requested? run)) nil

      (= "ready" (status run))
      {:harness/status "stopped"
       :harness/substatus "requested"
       :harness/stop-requested-at (now)
       :harness/stop-reason reason
       :harness/settled "true"
       :harness/settlement "never-launched"
       :harness/session-usable "false"}

      :else
      {:harness/substatus "requested"
       :harness/stop-requested-at (now)
       :harness/stop-reason reason})))

(defn migration-patch
  "Return a durable public-lifecycle patch for one legacy run, or nil.

  Legacy `done` rows keep their successful outcome but do not become
  provider-verified session-usable: old interactive Codex and Cursor stored
  provisional UUIDs that named no native history."
  [run]
  (when (and (= "true" (attr-get run :harness/run))
             (nil? (attr-get run :harness/status)))
    (let [phase (attr-get run :harness/phase)]
      (case phase
        "pending" {:harness/status "ready"
                   :harness/substatus "pending"
                   :harness/published "true"
                   :harness/phase nil}
        "running" {:harness/status "running"
                   :harness/substatus nil
                   :harness/published "true"
                   :harness/settled "false"
                   :harness/phase nil}
        "done" {:harness/status "stopped"
                :harness/substatus "completed"
                :harness/published "true"
                :harness/settled "true"
                :harness/settlement "legacy-success"
                :harness/session-usable "false"
                :harness/phase nil}
        "failed" {:harness/status "failed"
                  :harness/substatus "execution"
                  :harness/published "true"
                  ;; A legacy failure recorded no terminal custody fact, so it
                  ;; is not evidence that the provider process ever stopped.
                  :harness/settled "false"
                  :harness/session-usable "false"
                  :harness/phase nil}
        (fail! "Legacy harness run has an unknown phase"
               {:id (:id run) :phase phase})))))

(defn resume-eligibility
  "Return `{:eligible? bool :reason str}` for a native resume of `run`.

  A native resume reuses the predecessor's exact provider session, so it is
  allowed only when the run is terminal, its process is provably settled, and
  the provider has verified the session is still usable. `active-writers` is
  the count of other runs currently holding that session."
  [run active-writers]
  (let [session-id (attr-get run :harness/session-id)]
    (cond
      (not (terminal? run))
      {:eligible? false
       :reason (str "run is " (status run) ", not terminal")}

      (not (settled? run))
      {:eligible? false
       :reason (str "run is not settled ("
                    (or (attr-get run :harness/settlement) "no evidence") ")")}

      (str/blank? session-id)
      {:eligible? false :reason "run has no native session id"}

      (not (session-usable? run))
      {:eligible? false
       :reason "provider has not verified the native session as usable"}

      (pos? (long active-writers))
      {:eligible? false
       :reason (str active-writers " active run(s) already hold this session")}

      :else {:eligible? true :reason "settled with a verified usable session"})))

(defn fingerprint
  "Return a stable SHA-256 fingerprint for Clojure data.

  Map keys are ordered recursively so equivalent request maps converge."
  [value]
  (letfn [(canonical [v]
            (cond
              (map? v) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                             (map (fn [[k item]] [k (canonical item)]))
                             v)
              (set? v) (vec (sort-by pr-str (map canonical v)))
              (sequential? v) (mapv canonical v)
              :else v))]
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes (pr-str (canonical value)) "UTF-8"))]
      (str/join (map #(format "%02x" (bit-and 0xff %)) digest)))))

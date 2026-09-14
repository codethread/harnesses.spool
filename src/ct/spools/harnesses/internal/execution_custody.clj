(ns ct.spools.harnesses.internal.execution-custody
  "Generation-fenced reconciliation of Mill-owned headless processes."
  (:require [ct.spools.harnesses :as harness]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-receipts :as guidance-receipts]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.process-custody :as custody]
            [ct.spools.harnesses.internal.runs :as runs]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- launch-in-flight-opened? [opened run]
  (and (contains? #{nil "pending"} (attr-get run :harness/process-handle))
       (contains? @(:in-flight opened) (:id run))))

(defn launch-in-flight?
  "Return whether one generation still owns an unfinished launch."
  [{:keys [state]} rt run]
  (launch-in-flight-opened? (state rt) run))

(defn- same-attempt? [originating-run current]
  (and (= (attr-get originating-run :harness/attempt)
          (attr-get current :harness/attempt))
       (= (attr-get originating-run :harness/invocation)
          (attr-get current :harness/invocation))))

(defn inspect-owned!
  "Inspect and advance headless runs backed by Mill process custody."
  ([callbacks rt]
   (inspect-owned! callbacks rt ((:state callbacks) rt)))
  ([{:keys [active-opened? deadline-hook! enforce-stop! finish-process!
            full-run release-opened! resolved-definition schedule-inspection!]}
    rt opened]
   (when (active-opened? rt opened)
     (let [owned (runs/inspectable-headless
                  rt #(launch-in-flight-opened? opened %))]
       (when (seq owned)
         (let [records (custody/list-owned rt)
               generation (:generation opened)
               _ (deadline-hook!
                  {:phase :headless-before-publication-lock
                   :generation generation})
               outcome
               #_{:clj-kondo/ignore [:locking-suspicious-lock]}
               #_{:splint/disable [lint/locking-object]}
               (locking (catalog/publication-lock rt)
                 (when (active-opened? rt opened)
                   (let [recur? (atom false)
                         transition-errors (atom [])
                         failures (:reconciliation-failures opened)]
                     (doseq [originating-run owned]
                       (when-let [current (some->> (weaver/show
                                                    rt (:id originating-run))
                                                   (guidance/validation-run rt))]
                         (when (and (same-attempt? originating-run current)
                                    (= "headless"
                                       (attr-get current :harness/mode))
                                    (life/reserving? current)
                                    (not= "ready" (life/status current))
                                    (not (launch-in-flight-opened?
                                          opened current)))
                           (deadline-hook!
                            {:phase :headless-after-reload
                             :run-id (:id current)
                             :generation generation})
                           (try
                             (let [run (if (guidance/native? current)
                                         (guidance-receipts/expire! rt current)
                                         current)
                                   record (custody/record-for "harness" run records)
                                   durable
                                   (custody/durable-attributes
                                    "harness" (:id run)
                                    (attr-get run :harness/attempt) record)]
                               (swap! failures dissoc (:id run))
                               (when (= "pending"
                                        (attr-get run :harness/process-handle))
                                 (weaver/update! rt (:id run)
                                                 {:attributes durable}))
                               (if (= :terminal (:phase record))
                                 (let [current (full-run rt (:id run))]
                                   (finish-process!
                                    rt current
                                    (resolved-definition rt current) record))
                                 (do
                                   (enforce-stop! rt run record)
                                   (reset! recur? true))))
                             (catch Throwable error
                               (let [id (:id current)
                                     message
                                     (str "process custody reconciliation failed: "
                                          (ex-message error) " "
                                          (pr-str (ex-data error)))
                                     record
                                     (some #(when (= (:key %)
                                                     (attr-get
                                                      current
                                                      :harness/process-key))
                                              %)
                                           records)
                                     signature
                                     [id
                                      (attr-get current :harness/process-key)
                                      message]
                                     repeated?
                                     (and (nil? record)
                                          (life/terminal? current)
                                          (not (life/settled? current))
                                          (= signature (get @failures id)))
                                     transition-error
                                     (when-not repeated?
                                       (try
                                         (harness/finish!
                                          rt id
                                          (cond->
                                           {:status :failed
                                            :evidence
                                            {:settled false
                                             :settlement "no-terminal-evidence"
                                             :failure-class "reconciliation"}
                                            :error message}
                                            (some? (life/invocation current))
                                            (assoc :invocation
                                                   (life/invocation current))))
                                         (swap! failures assoc id signature)
                                         nil
                                         (catch Throwable transition-error
                                           transition-error)))]
                                 (release-opened! opened id)
                                 (when transition-error
                                   (when (and record
                                              (not= :terminal (:phase record))
                                              (= "running"
                                                 (life/status
                                                  (full-run rt id))))
                                     (reset! recur? true))
                                   (swap!
                                    transition-errors conj
                                    (ex-info
                                     "Unable to persist harness custody failure"
                                     {:run-id id
                                      :reconciliation-error
                                      {:run-id id
                                       :message (ex-message error)
                                       :data (ex-data error)}
                                      :failure-transition-error
                                      {:message
                                       (ex-message transition-error)
                                       :data (ex-data transition-error)}}
                                     transition-error)))))))))
                     {:recur? @recur?
                      :transition-errors @transition-errors})))]
           (when (:recur? outcome)
             (schedule-inspection! rt opened))
           (when (seq (:transition-errors outcome))
             (if (= 1 (count (:transition-errors outcome)))
               (throw (first (:transition-errors outcome)))
               (throw (ex-info
                       "Unable to persist harness custody failures"
                       {:failure-transition-errors
                        (mapv ex-data (:transition-errors outcome))}
                       (first (:transition-errors outcome))))))))))))

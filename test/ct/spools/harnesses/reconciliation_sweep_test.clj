(ns ct.spools.harnesses.reconciliation-sweep-test
  "Bounded scanning and durable reconciliation sweep contract tests."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.reconciliation :as reconciliation-api]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    {:deps
     {'ct.spools/harnesses
      {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity
      {:local/root (.getCanonicalPath identity-root)}}}))

(defn- full-world-options [storage]
  {:storage storage
   :deps-edn (pr-str (world-deps))
   :init-clj
   "(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity
              :required? true})
           (runtime/module! rt :harnesses-core
             {:file \"modules/lifecycle_core.clj\"
              :after [:identity]
              :required? true})"
   :files
   {"modules/lifecycle_core.clj"
    "(ns modules.lifecycle-core
       (:require [ct.spools.harnesses :as harnesses]
                 [ct.spools.harnesses.agent-cli :as agent-cli]
                 [ct.spools.harnesses.assignment :as assignment]
                 [ct.spools.harnesses.execution :as execution]
                 [ct.spools.harnesses.providers.claude :as claude]
                 [ct.spools.harnesses.providers.codex :as codex]
                 [ct.spools.harnesses.providers.cursor :as cursor]
                 [ct.spools.harnesses.providers.pi :as pi]
                 [ct.spools.harnesses.queries :as queries]
                 [ct.spools.harnesses.reconciliation :as reconcile]
                 [millstrand.api.lifecycle.alpha :as lifecycle]
                 [millstrand.api.millstrand.alpha :as millstrand]))
       (lifecycle/use-resource!
        harnesses/harness-core-runtime
        assignment/assignment-runtime
        claude/claude-harness-runtime
        codex/codex-harness-runtime
        cursor/cursor-harness-runtime
        pi/pi-harness-runtime
        execution/harness-execution-runtime)
       (lifecycle/use-reconcile!
        reconcile/interactive-reconciliation-sweep)
       (millstrand/use-op! agent-cli/agent)
       (millstrand/use-query!
        queries/agent-run-terminal
        queries/agent-run-settled
        queries/agent-run-active
        queries/agent-runs-active
        queries/agent-runs-for-target
        queries/agent-work-complete
        queries/agent-work-complete-or-intervention
        queries/agent-work-root-complete
        queries/agent-work-root-complete-or-intervention)"}})

(defn- core-world-options [storage]
  {:storage storage
   :deps-edn (pr-str (world-deps))
   :init-clj
   "(require '[millstrand.api.current.alpha :as current]
             '[millstrand.api.runtime.alpha :as runtime])
    (def rt (current/runtime))
    (runtime/module! rt :identity
      {:ns 'millhouse.spools.identity
       :required? true})
    (runtime/module! rt :harnesses-core
      {:file \"modules/lifecycle_core.clj\"
       :after [:identity]
       :required? true})"
   :files
   {"modules/lifecycle_core.clj"
    "(ns modules.lifecycle-core
       (:require [ct.spools.harnesses :as harnesses]
                 [ct.spools.harnesses.agent-cli :as agent-cli]
                 [ct.spools.harnesses.assignment :as assignment]
                 [ct.spools.harnesses.queries :as queries]
                 [millstrand.api.lifecycle.alpha :as lifecycle]
                 [millstrand.api.millstrand.alpha :as millstrand]))
     (lifecycle/use-resource!
      harnesses/harness-core-runtime
      assignment/assignment-runtime)
     (millstrand/use-op! agent-cli/agent)
     (millstrand/use-query!
      queries/agent-run-terminal
      queries/agent-run-settled
      queries/agent-run-active
      queries/agent-runs-active
      queries/agent-runs-for-target
      queries/agent-work-complete
      queries/agent-work-complete-or-intervention
      queries/agent-work-root-complete
      queries/agent-work-root-complete-or-intervention)"}})

(defn- with-core-world [f]
  (test-alpha/run-with-weaver-world (core-world-options :sqlite-memory) f))

(defn- create-temp-dir []
  (.toFile
   (Files/createTempDirectory
    (.toPath (io/file "/tmp"))
    "harnesses-reconciliation-restart-"
    (make-array FileAttribute 0))))

(defn- delete-tree! [root]
  (when (.exists root)
    (with-open [paths (Files/walk
                       (.toPath root)
                       (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (sort-by #(.getNameCount %) >
                            (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists path)))))

(deftest bounded-candidate-rotation-is-fair
  (let [rotate #'reconciliation-api/rotate-candidates
        candidates (mapv #(format "run-%03d" %) (range 1 102))
        first-batch (take 100 (rotate candidates 0))
        second-batch (take 100 (rotate candidates 100))]
    (is (= "run-001" (first first-batch)))
    (is (= "run-100" (last first-batch)))
    (is (= "run-101" (first second-batch)))
    (is (some #{"run-101"} second-batch))))

(deftest reconciliation-cadence-configuration-is-strict
  (let [parse-interval #'reconciliation-api/parse-sweep-interval]
    (is (= reconciliation-api/default-sweep-interval-ms
           (parse-interval nil)))
    (is (= 120000 (parse-interval "120000")))
    (is (nil? (parse-interval "disabled")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be positive"
                          (parse-interval "0")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be an integer"
                          (parse-interval "hourly")))))

(deftest bounded-manual-and-scheduled-scans-reach-later-orphans
  (test-alpha/run-with-weaver-world
   (full-world-options :sqlite-file)
   (fn [ctx]
     (let [result
           (test-alpha/repl!
            ctx
            '(do
               (require '[clojure.set :as set]
                        '[ct.spools.harnesses :as harnesses]
                        '[ct.spools.harnesses.reconciliation :as reconcile]
                        '[millstrand.api.current.alpha :as current]
                        '[millstrand.api.spool.alpha :as spool]
                        '[millstrand.api.weaver.alpha :as weaver])
               (let [rt (current/runtime)
                     runs
                     (mapv
                      (fn [_]
                        (let [run (harnesses/create!
                                   rt {:harness :pi :mode :interactive})]
                          (harnesses/begin-attempt! rt (:id run))))
                      (range 101))
                     ids (into #{} (map (comp :id :strand)) runs)
                     first-manual
                     (with-redefs [reconcile/native-observation
                                   (constantly {:state "not-observed"})]
                       (weaver/op! rt 'agent ["reconcile" "--dry-run"]))
                     first-ids (into #{} (map :id) (:runs first-manual))
                     orphan-id (first (set/difference ids first-ids))
                     orphan-start
                     (some #(when (= orphan-id (get-in % [:strand :id])) %)
                           runs)
                     pid (.pid (java.lang.ProcessHandle/current))
                     owner
                     (assoc (reconcile/completion-owner-attributes pid)
                            :harness/completion-owner-invocation
                            (:invocation orphan-start))
                     _ (weaver/update! rt orphan-id {:attributes owner})
                     _ (reconcile/register-provider!
                        rt orphan-id (:invocation orphan-start) pid)
                     _ (weaver/update!
                        rt orphan-id
                        {:attributes
                         {:harness/completion-owner-started-at
                          "1970-01-01T00:00:00Z"
                          :harness/provider-started-at
                          "1970-01-01T00:00:00Z"}})
                     second-manual
                     (with-redefs [reconcile/native-observation
                                   (constantly {:state "not-observed"})]
                       (weaver/op!
                        rt 'agent
                        ["reconcile" "--dry-run" "--offset"
                         (str (:next-offset first-manual))]))
                     first-payload (:payload
                                    (reconcile/actual-sweep {:runtime rt}))
                     first-scheduled
                     (with-redefs [reconcile/native-observation
                                   (constantly {:state "not-observed"})]
                       (reconcile/sweep-wake!
                        {:runtime rt :payload first-payload}))
                     second-payload (:payload
                                     (reconcile/actual-sweep {:runtime rt}))
                     second-scheduled
                     (with-redefs [reconcile/native-observation
                                   (constantly {:state "not-observed"})]
                       (reconcile/sweep-wake!
                        {:runtime rt :payload second-payload}))
                     stored (weaver/show rt orphan-id)]
                 {:orphan-id orphan-id
                  :first-manual
                  {:count (count (:runs first-manual))
                   :truncated (:truncated first-manual)
                   :next-offset (:next-offset first-manual)
                   :contains-orphan (contains? first-ids orphan-id)}
                  :second-manual
                  {:first-id (get-in second-manual [:runs 0 :id])
                   :classification
                   (get-in second-manual [:runs 0 :classification])}
                  :first-scheduled (:changed first-scheduled)
                  :second-scheduled (:changed second-scheduled)
                  :stored
                  {:status (spool/attr-get stored :harness/status)
                   :substatus (spool/attr-get stored
                                              :harness/substatus)}})))]
       (is (= {:count 100
               :truncated true
               :next-offset 100
               :contains-orphan false}
              (:first-manual result)))
       (is (= {:first-id (:orphan-id result)
               :classification "orphaned"}
              (:second-manual result)))
       (is (= [] (:first-scheduled result)))
       (is (= [(:orphan-id result)] (:second-scheduled result)))
       (is (= {:status "stopped" :substatus "abandoned"}
              (:stored result)))))))

(deftest durable-sweep-preserves-cadence-rearms-and-disables
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses.reconciliation :as reconcile]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.scheduler.alpha :as scheduler])
                (let [rt (current/runtime)
                      desired {:enabled true :interval-ms 3600000}
                      _ (reconcile/apply-sweep!
                         {:runtime rt
                          :desired {:enabled false :interval-ms nil}})
                      first-apply
                      (reconcile/apply-sweep!
                       {:runtime rt :desired desired :actual nil})
                      first-wake (reconcile/actual-sweep {:runtime rt})
                      second-apply
                      (reconcile/apply-sweep!
                       {:runtime rt :desired desired :actual first-wake})
                      second-wake (reconcile/actual-sweep {:runtime rt})
                      failure
                      (with-redefs [reconcile/reconcile!
                                    (fn [_runtime _opts]
                                      (throw (ex-info "forced sweep failure" {})))]
                        (try
                          (reconcile/sweep-wake!
                           {:runtime rt :payload (:payload first-wake)})
                          nil
                          (catch clojure.lang.ExceptionInfo error
                            (ex-message error))))
                      rearmed (reconcile/actual-sweep {:runtime rt})
                      runtime-stop
                      (reconcile/remove-sweep!
                       {:runtime rt :effect/phase :runtime-stop})
                      after-runtime-stop
                      (reconcile/actual-sweep {:runtime rt})
                      disabled
                      (reconcile/apply-sweep!
                       {:runtime rt
                        :desired {:enabled false :interval-ms nil}
                        :actual rearmed})]
                  {:first-apply first-apply
                   :second-apply second-apply
                   :same-wake-at (= (:wake_at first-wake)
                                    (:wake_at second-wake))
                   :handler (:handler first-wake)
                   :payload (:payload first-wake)
                   :failure failure
                   :rearmed? (some? rearmed)
                   :rearmed-offset (get-in rearmed [:payload :offset])
                   :runtime-stop runtime-stop
                   :runtime-stop-preserved?
                   (= (:wake_at rearmed) (:wake_at after-runtime-stop))
                   :disabled disabled
                   :pending (scheduler/pending rt)})))]
        (is (= :scheduled (get-in result [:first-apply :wake])))
        (is (= :preserved (get-in result [:second-apply :wake])))
        (is (true? (:same-wake-at result)))
        (is (= 'ct.spools.harnesses.reconciliation/sweep-wake!
               (:handler result)))
        (is (= {:interval-ms 3600000 :offset 0}
               (select-keys (:payload result) [:interval-ms :offset])))
        (is (string? (get-in result [:payload :generation])))
        (is (= "forced sweep failure" (:failure result)))
        (is (true? (:rearmed? result)))
        (is (= 100 (:rearmed-offset result)))
        (is (= :preserved (get-in result [:runtime-stop :status])))
        (is (true? (:runtime-stop-preserved? result)))
        (is (= :disabled (get-in result [:disabled :wake])))
        (is (= [] (:pending result)))))))

(deftest durable-sweep-deadline-survives-runtime-restart
  (let [root (create-temp-dir)
        opts (assoc (full-world-options :sqlite-file) :root root)]
    (try
      (let [first-wake
            (test-alpha/run-with-weaver-world
             opts
             (fn [{:keys [runtime]}]
               (test-alpha/await-quiescent! runtime {:timeout-ms 5000})
               (reconciliation-api/actual-sweep {:runtime runtime})))
            second-wake
            (test-alpha/run-with-weaver-world
             opts
             (fn [{:keys [runtime]}]
               (test-alpha/await-quiescent! runtime {:timeout-ms 5000})
               (reconciliation-api/actual-sweep {:runtime runtime})))]
        (is (some? first-wake))
        (is (= (:wake_at first-wake) (:wake_at second-wake)))
        (is (= (:payload first-wake) (:payload second-wake))))
      (finally
        (delete-tree! root)))))

(deftest sweep-configuration-serializes-with-an-entered-fire
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses.reconciliation :as reconcile]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.scheduler.alpha :as scheduler])
                (let [rt (current/runtime)
                      desired {:enabled true :interval-ms 3600000}
                      run-race
                      (fn [change!]
                        (reconcile/apply-sweep!
                         {:runtime rt :desired desired})
                        (let [wake (reconcile/actual-sweep {:runtime rt})
                              started (promise)
                              release (promise)
                              changed-started (promise)
                              calls (atom 0)
                              sweeping
                              (future
                                (with-redefs
                                 [reconcile/reconcile!
                                  (fn [_runtime _opts]
                                    (swap! calls inc)
                                    (deliver started true)
                                    @release
                                    {:changed []})]
                                  (reconcile/sweep-wake!
                                   {:runtime rt :payload (:payload wake)})))
                              _ (deref started 5000 false)
                              change-result (promise)
                              changing
                              (Thread.
                               (fn []
                                 (deliver changed-started true)
                                 (deliver change-result (change! rt))))
                              _ (.start changing)
                              _ (deref changed-started 5000 false)
                              blocked?
                              (loop [remaining 10000]
                                (cond
                                  (= java.lang.Thread$State/BLOCKED
                                     (.getState changing))
                                  true

                                  (zero? remaining)
                                  false

                                  :else
                                  (do (Thread/yield)
                                      (recur (dec remaining)))))
                              _ (deliver release true)
                              _ (deref sweeping 5000 false)
                              result (deref change-result 5000 false)
                              _ (.join changing 5000)]
                          {:blocked? blocked?
                           :calls @calls
                           :change-result result
                           :pending (scheduler/pending rt)}))
                      disabled
                      (run-race
                       (fn [runtime]
                         (reconcile/apply-sweep!
                          {:runtime runtime
                           :desired {:enabled false :interval-ms nil}})))
                      removed
                      (run-race
                       (fn [runtime]
                         (reconcile/remove-sweep! {:runtime runtime})))
                      _ (reconcile/apply-sweep!
                         {:runtime rt :desired desired})
                      stale-payload
                      (:payload (reconcile/actual-sweep {:runtime rt}))
                      _ (reconcile/apply-sweep!
                         {:runtime rt
                          :desired {:enabled false :interval-ms nil}})
                      _ (reconcile/apply-sweep!
                         {:runtime rt :desired desired})
                      stale-calls (atom 0)
                      stale-result
                      (with-redefs [reconcile/reconcile!
                                    (fn [_runtime _opts]
                                      (swap! stale-calls inc))]
                        (reconcile/sweep-wake!
                         {:runtime rt :payload stale-payload}))]
                  {:disabled disabled
                   :removed removed
                   :stale-result stale-result
                   :stale-calls @stale-calls})))]
        (doseq [operation [:disabled :removed]]
          (is (true? (get-in result [operation :blocked?])))
          (is (= 1 (get-in result [operation :calls])))
          (is (= [] (get-in result [operation :pending]))))
        (is (= :sweep-disabled-or-reconfigured
               (get-in result [:stale-result :reason])))
        (is (zero? (:stale-calls result)))))))

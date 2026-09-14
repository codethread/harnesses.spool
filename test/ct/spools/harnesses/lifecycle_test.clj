(ns ct.spools.harnesses.lifecycle-test
  "Lifecycle contract tests: status/substatus, stop, resume, and wait queries."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.cli :as cli]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.reconciliation :as reconciliation]
            [ct.spools.harnesses.reconciliation :as reconciliation-api]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- run
  ([id status] (run id status nil))
  ([id status substatus]
   {:id id
    :attributes (cond-> {:harness/run "true"
                         :harness/status status}
                  substatus (assoc :harness/substatus substatus))}))

(deftest running-has-no-pending-substatus
  (is (nil? (life/substatus (run "r" "running"))))
  (is (nil? (life/substatus {:attributes {:harness/run "true"
                                          :harness/phase "running"}})))
  (is (= "pending" (life/substatus (run "r" "ready" "pending")))))

(deftest reserving-includes-unsettled-terminal
  (is (true? (life/reserving? (run "r" "ready" "pending"))))
  (is (true? (life/reserving? (run "r" "running"))))
  (is (true? (life/reserving?
              {:id "r"
               :attributes {:harness/status "failed"
                            :harness/substatus "execution"
                            :harness/settled "false"}})))
  (is (false? (life/reserving?
               {:id "r"
                :attributes {:harness/status "failed"
                             :harness/substatus "execution"
                             :harness/settled "true"}})))
  (is (false? (life/reserving?
               {:id "r"
                :attributes {:harness/status "stopped"
                             :harness/substatus "completed"
                             :harness/settled "true"}}))))

(deftest interactive-reconciliation-preserves-ambiguity-and-live-custody
  (let [running (assoc-in (run "legacy" "running" "requested")
                          [:attributes :harness/harness] "pi")
        classify #(reconciliation/classification running %)]
    (testing "legacy and unavailable observations stay unknown"
      (is (= "unknown"
             (:classification
              (classify {:completion-owner {:state "missing"}
                         :provider {:state "missing"}
                         :native {:state "not-observed"}}))))
      (is (= "unknown"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "gone"}
                         :native {:state "unavailable"}})))))
    (testing "live, idle, and newer native writers are protected"
      (is (= "live"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "live"}
                         :native {:state "not-observed"}}))))
      (is (= "live"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "gone"}
                         :native {:state "active"}}))))
      (is (= "protected"
             (:classification
              (classify {:completion-owner {:state "gone"}
                         :provider {:state "gone"}
                         :native {:state "not-observed"}
                         :active-session-writers ["newer"]}))))
      (doseq [provider-state ["gone" "missing" "unavailable"]]
        (is (= "protected"
               (:classification
                (classify {:completion-owner {:state "live"}
                           :provider {:state provider-state}
                           :native {:state "not-observed"}}))))))
    (testing "both absent or PID-reused identities prove orphaned custody"
      (doseq [state ["gone" "replaced"]]
        (is (= "orphaned"
               (:classification
                (classify {:completion-owner {:state state}
                           :provider {:state state}
                           :native {:state "not-observed"}})))))))
  (testing "abandonment retains target and session reservations"
    (is (true? (life/reserving? (run "old" "stopped" "abandoned")))))
  (testing "the abandonment patch never fabricates process-exit evidence"
    (let [patch (reconciliation/abandonment-patch
                 (run "old" "running")
                 {:at "2026-09-13T00:00:00Z"
                  :by "operator"
                  :reason "launcher is unrecoverable"
                  :source "attested"
                  :evidence {:provider {:state "missing"}}})
          attributes (:attributes patch)]
      (is (= "closed" (:state patch)))
      (is (= "abandoned" (:harness/substatus attributes)))
      (is (= "false" (:harness/settled attributes)))
      (is (= "interactive-abandoned" (:harness/settlement attributes)))
      (is (not (contains? attributes :harness/exit-code))))))

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

(deftest terminal-patch-stop-races
  (let [running {:attributes {:harness/status "running"
                              :harness/stop-requested-at "t"}}
        ready {:attributes {:harness/status "ready"}}]
    (testing "normal completion racing stop stays completed"
      (is (= "completed"
             (:harness/substatus
              (life/terminal-patch running :done
                                   {:settled true :settlement "process-exit"}
                                   true))))
      (is (= "stopped"
             (:harness/status
              (life/terminal-patch running :done
                                   {:settled true :settlement "process-exit"}
                                   true)))))
    (testing "confirmed cancellation is requested"
      (is (= {:harness/status "stopped" :harness/substatus "requested"}
             (select-keys (life/terminal-patch
                           running :failed
                           {:settled true :settlement "graceful-cancellation"}
                           true)
                          [:harness/status :harness/substatus]))))
    (testing "prior failure remains failed"
      (is (= "failed"
             (:harness/status
              (life/terminal-patch ready :failed
                                   {:settled true :settlement "process-exit"}
                                   false))))
      (is (= "execution"
             (:harness/substatus
              (life/terminal-patch
               (assoc-in ready [:attributes :harness/invocation] "inv")
               :failed
               {:settled true :settlement "process-exit"}
               false)))))))

(deftest settlement-evidence-respects-mill-stop-class
  (is (= {:settled true :settlement "process-exit"}
         (life/settlement-evidence {:exit-code 0})))
  (is (= {:settled true :settlement "graceful-cancellation"}
         (life/settlement-evidence {:cancellation {:reason "stop"
                                                   :stop :graceful}})))
  (is (= "forced-cancellation"
         (:settlement (life/settlement-evidence
                       {:cancellation {:reason "stop" :stop :forced}}))))
  (is (false? (:settled (life/settlement-evidence
                         {:cancellation {:reason "stop" :stop :forced}}))))
  (is (false? (:settled (life/settlement-evidence
                         {:cancellation {:reason "cancelled by owner"}})))))

(deftest legacy-done-does-not-infer-session-usable
  (let [patch (life/migration-patch
               {:id "old"
                :attributes {:harness/run "true"
                             :harness/phase "done"
                             :harness/session-id "provisional-uuid"}})]
    (is (= "stopped" (:harness/status patch)))
    (is (= "completed" (:harness/substatus patch)))
    (is (= "false" (:harness/session-usable patch)))))

(deftest agent-await-is-removed
  (is (not (contains? (:subcommands cli/agent-arg-spec) "await"))))

(deftest reserved-agent-environment-cannot-be-overridden
  (let [runtime {:metadata {:config-dir "/runtime/workspace"}}
        run {:id "run-1"
             :attributes {:harness/cwd "/work"
                          :identity/id "agent-1"}}
        launch-spec {:argv ["agent"]
                     :env {"MILLSTRAND_RUN_ID" "attacker-run"
                           "MILLSTRAND_AGENT_ID" "attacker-agent"
                           "MILLSTRAND_WORKSPACE" "/wrong/workspace"
                           "OTHER" "kept"}
                     :stdin nil}
        launch (#'execution/process-spec runtime run launch-spec)]
    (is (= "run-1" (get-in launch [:env "MILLSTRAND_RUN_ID"])))
    (is (= "agent-1" (get-in launch [:env "MILLSTRAND_AGENT_ID"])))
    (is (= "/runtime/workspace"
           (get-in launch [:env "MILLSTRAND_WORKSPACE"])))
    (is (= "kept" (get-in launch [:env "OTHER"])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"no configured workspace"
         (#'execution/process-spec {:metadata {}} run launch-spec)))))

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

(deftest explicit-legacy-abandonment-is-auditable-and-idempotent
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.reconciliation :as reconcile]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      _ (harnesses/register-harness!
                         rt :pi
                         {:modes #{:interactive}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      pid (.pid (java.lang.ProcessHandle/current))
                      start-attributes (reconcile/completion-owner-attributes pid)
                      live (harnesses/create!
                            rt {:harness :pi :mode :interactive})
                      live-start (harnesses/begin-attempt!
                                  rt (:id live) start-attributes)
                      _ (reconcile/register-provider!
                         rt (:id live) (:invocation live-start) pid)
                      live-result
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (weaver/op! rt 'agent ["reconcile" (:id live)]))
                      live-abandon-refused?
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (try
                          (weaver/op!
                           rt 'agent
                           ["reconcile" (:id live) "--abandon"
                            "--reason" "must not override live evidence"
                            "--by-identity"
                            (spool/attr-get live :identity/id)])
                          false
                          (catch clojure.lang.ExceptionInfo _ true)))
                      reused (harnesses/create!
                              rt {:harness :pi :mode :interactive})
                      reused-start (harnesses/begin-attempt!
                                    rt (:id reused) start-attributes)
                      _ (reconcile/register-provider!
                         rt (:id reused) (:invocation reused-start) pid)
                      _ (weaver/update!
                         rt (:id reused)
                         {:attributes
                          {:harness/completion-owner-started-at
                           "1970-01-01T00:00:00Z"
                           :harness/provider-started-at
                           "1970-01-01T00:00:00Z"}})
                      reused-result
                      (with-redefs [reconcile/native-observation
                                    (constantly {:state "not-observed"})]
                        (weaver/op! rt 'agent ["reconcile" (:id reused)]))
                      target (weaver/add! rt {:title "Reconciliation target"})
                      legacy (harnesses/create!
                              rt {:harness :pi :mode :interactive
                                  :target (:id target)})
                      _ (harnesses/begin-attempt! rt (:id legacy))
                      actor (spool/attr-get legacy :identity/id)
                      before-dry-run (weaver/show rt (:id legacy))
                      dry-run (weaver/op! rt 'agent
                                          ["reconcile" (:id legacy) "--dry-run"])
                      dry-run-unchanged?
                      (= before-dry-run (weaver/show rt (:id legacy)))
                      abandoned
                      (weaver/op! rt 'agent
                                  ["reconcile" (:id legacy) "--abandon"
                                   "--reason" "launcher ownership was lost"
                                   "--by-identity" actor])
                      repeated
                      (weaver/op! rt 'agent
                                  ["reconcile" (:id legacy) "--abandon"
                                   "--reason" "launcher ownership was lost"
                                   "--by-identity" actor])
                      stored (weaver/show rt (:id legacy))
                      intervention?
                      (boolean
                       (seq (weaver/list-query
                             rt 'agent-work-complete-or-intervention
                             {:target (:id target)})))
                      target-blocked?
                      (try
                        (harnesses/create!
                         rt {:harness :pi :mode :interactive
                             :target (:id target)})
                        false
                        (catch clojure.lang.ExceptionInfo _ true))
                      same-session-blocked?
                      (try
                        (harnesses/create!
                         rt {:harness :pi :mode :interactive
                             :session-id
                             (spool/attr-get stored :harness/session-id)})
                        false
                        (catch clojure.lang.ExceptionInfo _ true))]
                  {:live live-result
                   :live-abandon-refused? live-abandon-refused?
                   :reused reused-result
                   :dry-run dry-run
                   :dry-run-unchanged? dry-run-unchanged?
                   :abandoned abandoned
                   :repeated repeated
                   :stored
                   {:status (spool/attr-get stored :harness/status)
                    :substatus (spool/attr-get stored :harness/substatus)
                    :settled (spool/attr-get stored :harness/settled)
                    :exit-code (spool/attr-get stored :harness/exit-code)
                    :reason (spool/attr-get stored :harness/abandon-reason)
                    :source (spool/attr-get stored
                                            :harness/reconciliation-source)}
                   :intervention? intervention?
                   :target-blocked? target-blocked?
                   :same-session-blocked? same-session-blocked?})))]
        (is (= [] (get-in result [:live :changed])))
        (is (= "live" (get-in result [:live :runs 0 :classification])))
        (is (true? (:live-abandon-refused? result)))
        (is (= [(get-in result [:reused :runs 0 :id])]
               (get-in result [:reused :changed])))
        (is (= "replaced"
               (get-in result
                       [:reused :runs 0 :evidence :provider :state])))
        (is (= "replaced"
               (get-in result
                       [:reused :runs 0 :evidence
                        :completion-owner :state])))
        (is (= [] (get-in result [:dry-run :changed])))
        (is (true? (:dry-run-unchanged? result)))
        (is (= "unknown"
               (get-in result [:dry-run :runs 0 :classification])))
        (is (= [(:id (first (get-in result [:abandoned :runs])))]
               (get-in result [:abandoned :changed])))
        (is (= [] (get-in result [:repeated :changed])))
        (is (= {:status "stopped"
                :substatus "abandoned"
                :settled "false"
                :exit-code nil
                :reason "launcher ownership was lost"
                :source "attested"}
               (:stored result)))
        (is (true? (:intervention? result)))
        (is (true? (:target-blocked? result)))
        (is (true? (:same-session-blocked? result)))))))

(deftest bounded-manual-and-scheduled-scans-reach-later-orphans
  (test-alpha/run-with-weaver-world
   (full-world-options :sqlite-memory)
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

(deftest work-scope-queries-use-positive-evidence
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.assignment :as assignment]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.graph.alpha :as graph]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      attr spool/attr-get
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:headless}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      hierarchy
                      (fn [prefix]
                        (let [task (weaver/add!
                                    rt {:title (str prefix " task")
                                        :attributes {:kanban/card "true"
                                                     :kanban/type "task"}})
                              feature (weaver/add!
                                       rt {:title (str prefix " feature")
                                           :attributes {:kanban/card "true"
                                                        :kanban/type "feature"}
                                           :edges [{:type "parent-of"
                                                    :to (:id task)}]})
                              epic (weaver/add!
                                    rt {:title (str prefix " epic")
                                        :attributes {:kanban/card "true"
                                                     :kanban/type "epic"}
                                        :edges [{:type "parent-of"
                                                 :to (:id feature)}]})]
                          {:epic epic :feature feature :task task}))
                      assign (fn [task options]
                               (assignment/assign!
                                rt (merge {:harness :fake
                                           :target (:id task)
                                           :cwd "/tmp/assignment-work"}
                                          options)))
                      queried? (fn [query target]
                                 (seq (weaver/list-query
                                       rt query {:target (:id target)})))
                      failed-scope (hierarchy "Failed")
                      failed-run (assign (:task failed-scope) {})
                      _ (harnesses/finish! rt (:id failed-run)
                                           {:status :failed :error "boom"})
                      failed-roots (mapv :to_strand_id
                                         (graph/outgoing-edges
                                          rt [(:id failed-run)] "serves-root"))
                      abandoned-scope (hierarchy "Abandoned")
                      abandoned-run (assign (:task abandoned-scope) {})
                      _ (harnesses/stop! rt (:id abandoned-run) {})
                      _ (weaver/update!
                         rt (get-in abandoned-scope [:task :id])
                         {:state "closed"
                          :attributes {:kanban/outcome "abandoned"}})
                      superseded-scope (hierarchy "Superseded")
                      predecessor (assign (:task superseded-scope)
                                          {:request-id "predecessor"})
                      _ (harnesses/finish!
                         rt (:id predecessor)
                         {:status :failed
                          :error "retryable"
                          :evidence {:settled true
                                     :settlement "process-exit"}})
                      continuation (assign (:task superseded-scope)
                                           {:request-id "continuation"
                                            :after (:id predecessor)})
                      ambiguous-task (weaver/add!
                                      rt {:title "Ambiguous task"
                                          :attributes {:kanban/card "true"}})
                      _ (doseq [title ["Parent A" "Parent B"]]
                          (weaver/add!
                           rt {:title title
                               :attributes {:kanban/card "true"}
                               :edges [{:type "parent-of"
                                        :to (:id ambiguous-task)}]}))
                      ambiguous (try
                                  (assign ambiguous-task {})
                                  :accepted
                                  (catch Exception error (ex-message error)))]
                  {:failed
                   {:feature (boolean (queried?
                                       'agent-work-root-complete-or-intervention
                                       (:feature failed-scope)))
                    :epic (boolean (queried?
                                    'agent-work-root-complete-or-intervention
                                    (:epic failed-scope)))
                    :roots (set failed-roots)
                    :expected-roots #{(get-in failed-scope [:feature :id])
                                      (get-in failed-scope [:epic :id])}}
                   :abandoned
                   {:feature (boolean (queried?
                                       'agent-work-root-complete-or-intervention
                                       (:feature abandoned-scope)))
                    :epic (boolean (queried?
                                    'agent-work-root-complete-or-intervention
                                    (:epic abandoned-scope)))
                    :complete (boolean (queried?
                                        'agent-work-root-complete
                                        (:epic abandoned-scope)))
                    :run-status (attr (weaver/show rt (:id abandoned-run))
                                      :harness/status)}
                   :malformed {:ambiguous ambiguous}
                   :superseded
                   {:epic (boolean (queried?
                                    'agent-work-root-complete-or-intervention
                                    (:epic superseded-scope)))
                    :continued (attr (weaver/show rt (:id predecessor))
                                     :harness/continued)
                    :continuation (:id continuation)}})))]
        (testing "a current task-run failure wakes every frozen ancestor"
          (is (= (:expected-roots (:failed result))
                 (:roots (:failed result))))
          (is (true? (get-in result [:failed :feature])))
          (is (true? (get-in result [:failed :epic]))))
        (testing "an abandoned task wakes ancestors but is not completion"
          (is (= "stopped" (get-in result [:abandoned :run-status])))
          (is (true? (get-in result [:abandoned :feature])))
          (is (true? (get-in result [:abandoned :epic])))
          (is (false? (get-in result [:abandoned :complete]))))
        (testing "an accepted continuation excludes its failed predecessor"
          (is (= "true" (get-in result [:superseded :continued])))
          (is (string? (get-in result [:superseded :continuation])))
          (is (false? (get-in result [:superseded :epic]))))
        (testing "ambiguous parent scopes fail loudly"
          (is (re-find #"ambiguous work-card parents"
                       (get-in result [:malformed :ambiguous]))))))))

(deftest custody-inspection-accepts-terminal-runs-without-an-invocation
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.execution :as execution]
                         '[ct.spools.harnesses.internal.process-custody :as custody]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:headless}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      run (harnesses/create!
                           rt {:harness :fake :mode :headless :cwd "/tmp"
                               :prompt "Do not launch this failed run."
                               :title "Unsettled terminal run"})
                      id (:id run)
                      _ (harnesses/finish!
                         rt id {:status :failed :error "prior failure"})
                      _ (weaver/update!
                         rt id
                         {:attributes {:harness/attempt 1
                                       :harness/process-owner "agent-harness/run"
                                       :harness/process-key (str id "/attempt-1")
                                       :harness/process-handle "missing"}})]
                  (with-redefs [custody/list-owned (constantly [])]
                    (let [opened (execution/open-execution! {:runtime rt})]
                      (try
                        (execution/inspect-owned! rt)
                        (let [retained (weaver/show rt id)]
                          {:deferred? (contains? opened :deferred-recovery)
                           :status (spool/attr-get retained :harness/status)
                           :settled (spool/attr-get retained :harness/settled)
                           :invocation (spool/attr-get retained :harness/invocation)
                           :error (spool/attr-get retained :harness/error)})
                        (finally
                          (execution/close-execution! {:runtime rt}))))))))]
        (is (= {:deferred? false :status "failed" :settled "false"
                :invocation nil :error "prior failure"}
               result))))))

(deftest custody-inspection-coalesces-and-deduplicates-failures
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.execution :as execution]
                         '[ct.spools.harnesses.internal.process-custody :as custody]
                         '[ct.spools.harnesses.internal.runs :as runs]
                         '[millstrand.api.current.alpha :as current])
                (let [rt (current/runtime)
                      running (mapv (fn [id]
                                      {:id id
                                       :attributes
                                       {:harness/status "running"
                                        :harness/settled "false"
                                        :harness/attempt 1
                                        :harness/process-owner "agent-harness/run"
                                        :harness/process-key (str id "/attempt-1")
                                        :harness/process-handle (str "handle-" id)}})
                                    ["one" "two"])
                      records (mapv (fn [run]
                                      {:owner custody/owner
                                       :key (get-in run [:attributes
                                                         :harness/process-key])
                                       :handle (get-in run [:attributes
                                                            :harness/process-handle])
                                       :phase :running})
                                    running)
                      terminal (assoc-in (first running)
                                         [:attributes :harness/status]
                                         "failed")
                      finish-count (atom 0)
                      schedule-count (atom 0)
                      _ (#'execution/activate-state! rt)]
                  (try
                    (with-redefs-fn
                      {#'runs/inspectable-headless (fn [_ _] running)
                       #'custody/list-owned (constantly records)
                       #'execution/schedule-inspection!
                       (fn [_] (swap! schedule-count inc))}
                      #(execution/inspect-owned! rt))
                    (with-redefs [runs/inspectable-headless
                                  (fn [_ _] [terminal])
                                  custody/list-owned (constantly [])
                                  harnesses/finish!
                                  (fn [_ _ _] (swap! finish-count inc))]
                      (execution/inspect-owned! rt)
                      (execution/inspect-owned! rt))
                    {:scheduled @schedule-count
                     :finish-count @finish-count
                     :state-version @#'execution/state-version
                     :state-keys (set (keys (#'execution/state rt)))}
                    (finally
                      ((:close-fn (#'execution/deactivate-state! rt))))))))]
        (testing "many live records schedule one runtime-wide recurring pass"
          (is (= 1 (:scheduled result))))
        (testing "an identical missing-custody failure is persisted once"
          (is (= 1 (:finish-count result))))
        (testing "runtime state version owns reconciliation coordination"
          (is (= 4 (:state-version result)))
          (is (every? (:state-keys result)
                      [:inspection-scheduled? :reconciliation-failures])))))))

(deftest late-successful-settlement-preserves-primary-failure
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool])
                (let [rt (current/runtime)
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:interactive}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      run (harnesses/create!
                           rt {:harness :fake
                               :mode :interactive
                               :title "late successful settlement"})
                      failed (harnesses/finish!
                              rt (:id run)
                              {:status :failed
                               :error "primary launch failure"})
                      settled (harnesses/settle-outcome!
                               rt (:id failed)
                               {:status :done
                                :exit-code 0
                                :result "released"
                                :session-usable true}
                               {:settled true
                                :settlement "process-exit"})]
                  {:status (spool/attr-get settled :harness/status)
                   :error (spool/attr-get settled :harness/error)
                   :exit-code (spool/attr-get settled :harness/exit-code)
                   :result (spool/attr-get settled :harness/result)
                   :settled (spool/attr-get settled :harness/settled)
                   :settlement (spool/attr-get settled
                                               :harness/settlement)})))]
        (is (= {:status "failed"
                :error "primary launch failure"
                :exit-code 0
                :result "released"
                :settled "true"
                :settlement "process-exit"}
               result))))))

(deftest lifecycle-contract-in-disposable-world
  (with-core-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.internal.lifecycle :as life]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)
                      attr spool/attr-get
                      _ (harnesses/register-harness!
                         rt :fake
                         {:modes #{:headless :interactive}
                          :prepare 'ct.spools.harnesses/create!
                          :finish 'ct.spools.harnesses/finish!})
                      cancelled (harnesses/create!
                                 rt {:harness :fake
                                     :mode :interactive
                                     :title "cancel-before-launch"})
                      cancelled (harnesses/stop! rt (:id cancelled)
                                                 {:reason "never start"})
                      start-after-stop
                      (try (harnesses/begin-attempt! rt (:id cancelled))
                           :started
                           (catch clojure.lang.ExceptionInfo _
                             :refused))
                      racing (harnesses/create!
                              rt {:harness :fake :mode :interactive
                                  :title "race"})
                      started (harnesses/begin-attempt! rt (:id racing))
                      racing (harnesses/stop! rt (:id racing)
                                              {:reason "stop now"})
                      racing-after-stop {:status (life/status racing)
                                         :substatus (life/substatus racing)
                                         :settled (life/settled? racing)}
                      missing-invocation
                      (try
                        (harnesses/finish! rt (:id racing)
                                           {:status :done :exit-code 0})
                        :accepted
                        (catch clojure.lang.ExceptionInfo e
                          (ex-message e)))
                      racing (harnesses/finish!
                              rt (:id racing)
                              {:status :done :exit-code 0
                               :invocation (:invocation started)
                               :session-usable true})
                      stale (harnesses/create!
                             rt {:harness :fake :mode :interactive
                                 :title "stale"})
                      stale-start (harnesses/begin-attempt! rt (:id stale))
                      stale-before-wrong (weaver/show rt (:id stale))
                      stale-after-wrong
                      (harnesses/finish!
                       rt (:id stale)
                       {:status :done :exit-code 0
                        :invocation "not-this-attempt"
                        :session-usable true})
                      stale-after-right
                      (harnesses/finish!
                       rt (:id stale)
                       {:status :done :exit-code 0
                        :invocation (:invocation stale-start)
                        :session-usable true})
                      failed (harnesses/create!
                              rt {:harness :fake :mode :interactive
                                  :session-id "failed-session"
                                  :title "failed"})
                      failed (harnesses/finish!
                              rt (:id failed)
                              {:status :failed :error "boom"})
                      failed-resume
                      (try (harnesses/resume! rt (:id failed) {})
                           :resumed
                           (catch clojure.lang.ExceptionInfo e
                             (ex-message e)))
                      conflict (try
                                 (harnesses/create!
                                  rt {:harness :fake :mode :interactive
                                      :session-id "failed-session"
                                      :title "conflict"})
                                 :created
                                 (catch clojure.lang.ExceptionInfo e
                                   (ex-message e)))
                      first-req (harnesses/create!
                                 rt {:harness :fake :mode :interactive
                                     :title "req"
                                     :request-id "req-1"})
                      same-req (harnesses/create!
                                rt {:harness :fake :mode :interactive
                                    :title "req"
                                    :request-id "req-1"})
                      conflict-req
                      (try (harnesses/create!
                            rt {:harness :fake :mode :interactive
                                :title "other"
                                :request-id "req-1"})
                           :created
                           (catch clojure.lang.ExceptionInfo e
                             {:message (ex-message e)
                              :run (get (ex-data e) :run)}))
                      target (weaver/add! rt {:title "served"})
                      head (harnesses/create!
                            rt {:harness :fake :mode :interactive
                                :title "head"
                                :target (:id target)
                                :context {:policy "frozen"}
                                :session-id "lineage"})
                      head (harnesses/finish!
                            rt (:id head)
                            {:status :done :exit-code 0
                             :session-usable true})
                      child (harnesses/resume!
                             rt (:id head)
                             {:request-id "resume-1"})
                      changed-cwd
                      (try (harnesses/resume! rt (:id head)
                                              {:cwd "/other"})
                           :resumed
                           (catch clojure.lang.ExceptionInfo e
                             (ex-message e)))
                      child-start (harnesses/begin-attempt! rt (:id child))
                      repeated (harnesses/resume!
                                rt (:id head)
                                {:request-id "resume-1"})
                      stale-head
                      (try (harnesses/resolve-resume-run
                            rt {:session-id "lineage"})
                           :resolved
                           (catch clojure.lang.ExceptionInfo e
                             (ex-message e)))
                      assigned-retry
                      (let [bound (harnesses/create!
                                   rt {:harness :fake :mode :interactive
                                       :request-id "assigned-1"
                                       :title "assigned"})
                            bound (harnesses/finish!
                                   rt (:id bound)
                                   {:status :failed
                                    :exit-code 1
                                    :error "no"})]
                        (try (harnesses/retry! rt (:id bound) {})
                             :retried
                             (catch clojure.lang.ExceptionInfo e
                               (ex-message e))))
                      missing (weaver/list-query
                               rt 'agent-run-terminal {:run-id "no-such-run"})
                      active (weaver/list-query
                              rt 'agent-run-active {:run-id (:id child)})
                      terminal (weaver/list-query
                                rt 'agent-run-terminal {:run-id (:id racing)})
                      settled (weaver/list-query
                               rt 'agent-run-settled {:run-id (:id racing)})
                      failed-terminal (weaver/list-query
                                       rt 'agent-run-terminal
                                       {:run-id (:id failed)})
                      failed-settled (weaver/list-query
                                      rt 'agent-run-settled
                                      {:run-id (:id failed)})]
                  {:stop-before-launch
                   {:status (life/status cancelled)
                    :substatus (life/substatus cancelled)
                    :settled (life/settled? cancelled)
                    :start start-after-stop}
                   :stop-vs-finish
                   {:after-stop racing-after-stop
                    :missing-invocation missing-invocation
                    :after-done {:status (life/status racing)
                                 :substatus (life/substatus racing)
                                 :settled (life/settled? racing)}}
                   :stale {:wrong-unchanged
                           (= stale-before-wrong stale-after-wrong)
                           :right-status (life/status stale-after-right)}
                   :failed-resume failed-resume
                   :session-conflict conflict
                   :duplicate {:same? (= (:id first-req) (:id same-req))
                               :conflict-run (:run conflict-req)
                               :first (:id first-req)}
                   :inherit {:target (attr child :harness/target)
                             :context (attr child :harness/context)
                             :logical (attr child :harness/logical-id)
                             :head-logical (life/logical-id head)}
                   :dedup-before-eligibility (= (:id child) (:id repeated))
                   :changed-cwd changed-cwd
                   :stale-head stale-head
                   :assigned-retry assigned-retry
                   :queries {:missing (mapv :id missing)
                             :active (mapv :id active)
                             :terminal (mapv :id terminal)
                             :settled (mapv :id settled)
                             :failed-terminal (mapv :id failed-terminal)
                             :failed-settled (mapv :id failed-settled)}
                   :child-running (life/status (weaver/show rt (:id child)))
                   :await-op (try (weaver/resolve-op rt 'agent)
                                  (get-in (weaver/resolve-op rt 'agent)
                                          [:arg-spec :subcommands])
                                  (catch Throwable _ {}))
                   :child-invocation (:invocation child-start)})))]
        (testing "stop before launch settles and cannot start"
          (is (= "stopped" (get-in result [:stop-before-launch :status])))
          (is (= "requested" (get-in result [:stop-before-launch :substatus])))
          (is (true? (get-in result [:stop-before-launch :settled])))
          (is (= :refused (get-in result [:stop-before-launch :start]))))
        (testing "stop stays running until a normal finish settles completed"
          (is (= {:status "running" :substatus "requested" :settled false}
                 (get-in result [:stop-vs-finish :after-stop])))
          (is (re-find #"requires its invocation token"
                       (get-in result [:stop-vs-finish :missing-invocation])))
          (is (= {:status "stopped" :substatus "completed" :settled true}
                 (get-in result [:stop-vs-finish :after-done]))))
        (testing "stale callbacks cannot finish a newer attempt"
          (is (true? (get-in result [:stale :wrong-unchanged])))
          (is (= "stopped" (get-in result [:stale :right-status]))))
        (testing "native resume preserves settings"
          (is (re-find #"cannot change cwd" (:changed-cwd result))))
        (testing "failed sessions are not natively resumable without usable evidence"
          (is (string? (:failed-resume result)))
          (is (re-find #"cannot be resumed" (:failed-resume result))))
        (testing "failed-unsettled rows keep the session reservation"
          (is (re-find #"active managed writer" (:session-conflict result))))
        (testing "duplicate requests converge and conflicts name the holder"
          (is (true? (get-in result [:duplicate :same?])))
          (is (= (get-in result [:duplicate :first])
                 (get-in result [:duplicate :conflict-run]))))
        (testing "resume inherits target/context and logical identity"
          (is (some? (get-in result [:inherit :target])))
          (is (= {:policy "frozen"} (get-in result [:inherit :context])))
          (is (= (get-in result [:inherit :head-logical])
                 (get-in result [:inherit :logical]))))
        (testing "request dedup wins before resume eligibility"
          (is (true? (:dedup-before-eligibility result)))
          (is (= "running" (:child-running result))))
        (testing "accepted running children block stale ancestor heads"
          (is (re-find #"No settled harness run head"
                       (:stale-head result))))
        (testing "request-bound assigned work cannot be retried in place"
          (is (re-find #"request-bound" (:assigned-retry result))))
        (testing "named queries return the target run only on verified evidence"
          (is (= [] (get-in result [:queries :missing])))
          (is (= 1 (count (get-in result [:queries :active]))))
          (is (= 1 (count (get-in result [:queries :terminal]))))
          (is (= 1 (count (get-in result [:queries :settled]))))
          (is (= 1 (count (get-in result [:queries :failed-terminal]))))
          (is (= [] (get-in result [:queries :failed-settled]))))
        (testing "agent await is not an agent subcommand"
          (is (not (contains? (:await-op result) "await"))))))))

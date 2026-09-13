(ns ct.spools.harnesses.managed-startup-test
  "Managed Codex/Pi reservation, startup attachment, and repair contracts."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millstrand.test.alpha :as test-alpha]))

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

(defn- with-managed-world [f]
  (test-alpha/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn (pr-str (world-deps))
          :init-clj
          "(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity
              :required? true})
           (runtime/module! rt :harnesses-core
             {:file \"modules/managed_core.clj\"
              :after [:identity]
              :required? true})"
          :files
          {"modules/managed_core.clj"
           "(ns modules.managed-core
              (:require [ct.spools.harnesses :as harnesses]
                        [millstrand.api.lifecycle.alpha :as lifecycle]))
            (lifecycle/use-resource! harnesses/harness-core-runtime)"}}]
    (f ctx)))

(def ^:private setup
  '(do
     (require '[clojure.data.json :as json]
              '[ct.spools.harnesses :as harnesses]
              '[ct.spools.harnesses.execution :as execution]
              '[ct.spools.harnesses.internal.launcher :as launcher]
              '[ct.spools.harnesses.internal.managed-startup :as managed]
              '[millhouse.spools.identity :as identity]
              '[millstrand.api.current.alpha :as current]
              '[millstrand.api.graph.alpha :as graph]
              '[millstrand.api.spool.alpha :as spool]
              '[millstrand.api.weaver.alpha :as weaver])
     (def rt (current/runtime))
     (doseq [provider [:codex :pi :claude :cursor]]
       (harnesses/register-harness!
        rt provider
        {:modes #{:headless :interactive}
         :prepare 'ct.spools.harnesses/create!
         :finish 'ct.spools.harnesses/finish!}))
     (defn attr [strand key] (spool/attr-get strand key))
     (defn failure [f]
       (try
         (f)
         nil
         (catch clojure.lang.ExceptionInfo error
           {:message (ex-message error) :data (ex-data error)})))
     (defn targets [strand type]
       (into #{} (map :to_strand_id)
             (graph/outgoing-edges rt [(:id strand)] type)))))

(defn- eval-world [ctx body]
  (test-alpha/repl! ctx (list 'do setup body)))

(deftest codex-reservation-attaches-and-native-resume-keeps-identity
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [caller (identity/startup!
                            rt {:harness "pi"
                                :native-session-id "desktop-parent"})
                    run (harnesses/create!
                         rt {:harness :codex
                             :mode :interactive
                             :cwd "/tmp/managed-codex"
                             :title "managed codex"
                             :append-system-prompt
                             "Frozen guidance for {{AGENT_ID}} / {{RUN_ID}}."
                             :by-identity (:identity caller)})
                    identity-before (identity/current rt (attr run :identity/id))
                    launcher-path (launcher/write! rt run ["codex"] {})
                    launcher-before (slurp launcher-path)
                    started (execution/mark-interactive-running! rt (:id run))
                    launcher-after (slurp launcher-path)
                    bootstrap (harnesses/managed-bootstrap rt (:id run))
                    process-spec (#'execution/process-spec
                                  rt started
                                  {:argv ["codex"] :env {} :stdin nil})
                    exported-bootstrap
                    (json/read-str
                     (get-in process-spec
                             [:env "MILLSTRAND_MANAGED_BOOTSTRAP"]))
                    attached (harnesses/managed-startup!
                              rt {:harness "codex"
                                  :native-session-id "thread-actual"
                                  :cwd "/tmp/managed-codex"
                                  :scope "root"
                                  :bootstrap bootstrap})
                    attached-at
                    (attr (weaver/show rt (:id run))
                          :harness/native-attached-at)
                    replay (harnesses/managed-startup!
                            rt {:harness "codex"
                                :native-session-id "thread-actual"
                                :cwd "/tmp/managed-codex"
                                :scope "root"
                                :bootstrap bootstrap})
                    finished (harnesses/finish!
                              rt (:id run)
                              {:status :done
                               :exit-code 0
                               :invocation (attr started
                                                 :harness/invocation)})
                    normal-repair
                    (failure #(harnesses/repair-managed-startup!
                               rt {:run-id (:id finished)
                                   :identity (attr finished :identity/id)
                                   :native-session-id "thread-actual"}))
                    resumed (harnesses/resume! rt (:id finished) {})
                    replay-after-resume
                    (harnesses/managed-startup!
                     rt {:harness "codex"
                         :native-session-id "thread-actual"
                         :cwd "/tmp/managed-codex"
                         :scope "root"
                         :bootstrap bootstrap})
                    replayed-at
                    (attr (weaver/show rt (:id run))
                          :harness/native-attached-at)
                    resumed-identity (identity/current
                                      rt (attr resumed :identity/id))
                    caller-strand (identity/current rt (:identity caller))]
                {:run (:id run)
                 :published (attr run :harness/published)
                 :reservation-state-before
                 (attr identity-before :identity/reservation-state)
                 :native-before
                 (attr identity-before :identity/native-session-id)
                 :launcher-before launcher-before
                 :launcher-after launcher-after
                 :bootstrap bootstrap
                 :exported-bootstrap exported-bootstrap
                 :attached attached
                 :replay replay
                 :replay-after-resume replay-after-resume
                 :attached-at attached-at
                 :replayed-at replayed-at
                 :finished-session (attr finished :harness/session-id)
                 :normal-repair normal-repair
                 :finished-usable (attr finished :harness/session-usable)
                 :same-resume-identity
                 (= (attr run :identity/id) (attr resumed :identity/id))
                 :same-resume-session
                 (= (attr finished :harness/session-id)
                    (attr resumed :harness/session-id))
                 :performed (targets resumed-identity "performed")
                 :parented (targets caller-strand "parent-of")
                 :child-strand (:id resumed-identity)}))]
        (is (= "true" (:published result)))
        (is (= "reserved" (:reservation-state-before result)))
        (is (nil? (:native-before result)))
        (testing "bootstrap is explicit, fenced, and carries no prompt bytes"
          (is (= "millstrand.agent-managed-bootstrap/v1"
                 (get-in result [:bootstrap "schema"])))
          (is (= "root" (get-in result [:bootstrap "scope"])))
          (is (pos-int? (get-in result [:bootstrap "attempt"])))
          (is (string? (get-in result [:bootstrap "invocation"])))
          (is (not-any? #(str/includes? % "prompt")
                        (keys (:bootstrap result))))
          (is (not (str/includes? (pr-str (:bootstrap result))
                                  "Frozen guidance")))
          (is (= (:bootstrap result) (:exported-bootstrap result)))
          (is (str/includes? (:launcher-before result)
                             "MILLSTRAND_MANAGED_BOOTSTRAP_PENDING"))
          (is (str/includes? (:launcher-after result)
                             "MILLSTRAND_MANAGED_BOOTSTRAP="))
          (is (not (str/includes? (:launcher-after result)
                                  "Frozen guidance"))))
        (is (= "attached" (get-in result [:attached :result])))
        (is (= (:attached result) (:replay result)
               (:replay-after-resume result)))
        (is (= (:attached-at result) (:replayed-at result)))
        (is (= "millstrand.agent-managed-context/v1"
               (get-in result [:attached :context :schema])))
        (is (= 1 (count (get-in result
                                [:attached :context
                                 :appended-system-prompts]))))
        (is (= "thread-actual" (:finished-session result)))
        (is (re-find #"only to legacy"
                     (get-in result [:normal-repair :message])))
        (is (= "true" (:finished-usable result)))
        (is (true? (:same-resume-identity result)))
        (is (true? (:same-resume-session result)))
        (is (= 2 (count (:performed result))))
        (is (= #{(:child-strand result)} (:parented result)))))))

(deftest stale-child-mismatch-and-writer-conflicts-do-not-attach
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [codex (harnesses/create!
                           rt {:harness :codex :mode :interactive
                               :cwd "/tmp/root" :title "root"})
                    started (harnesses/begin-attempt! rt (:id codex))
                    bootstrap (harnesses/managed-bootstrap rt (:id codex))
                    identity-id (attr codex :identity/id)
                    try-start (fn [native scope document]
                                (failure
                                 #(harnesses/managed-startup!
                                   rt {:harness "codex"
                                       :native-session-id native
                                       :cwd "/tmp/root"
                                       :scope scope
                                       :bootstrap document})))
                    stale (try-start "actual"
                                     "root"
                                     (assoc bootstrap "invocation" "stale"))
                    child (try-start "actual" "child" bootstrap)
                    writer (harnesses/create!
                            rt {:harness :codex :mode :interactive
                                :cwd "/tmp/other" :session-id "actual"
                                :title "writer"})
                    conflict (try-start "actual" "root" bootstrap)
                    before-stop (identity/current rt identity-id)
                    _ (harnesses/stop! rt (:id writer) {:reason "release"})
                    attached (try-start "actual" "root" bootstrap)
                    different (try-start "other-native" "root" bootstrap)
                    after (identity/current rt identity-id)
                    pi-run (harnesses/create!
                            rt {:harness :pi :mode :interactive
                                :cwd "/tmp/pi" :session-id "pi-expected"
                                :title "pi"})
                    _ (harnesses/begin-attempt! rt (:id pi-run))
                    pi-bootstrap (harnesses/managed-bootstrap rt (:id pi-run))
                    pi-mismatch
                    (failure #(harnesses/managed-startup!
                               rt {:harness "pi"
                                   :native-session-id "pi-wrong"
                                   :cwd "/tmp/pi"
                                   :scope "root"
                                   :bootstrap pi-bootstrap}))]
                {:attempt (:attempt started)
                 :stale stale
                 :child child
                 :conflict conflict
                 :before-state
                 (attr before-stop :identity/reservation-state)
                 :before-native
                 (attr before-stop :identity/native-session-id)
                 :attached attached
                 :different different
                 :after-state (attr after :identity/reservation-state)
                 :after-native (attr after :identity/native-session-id)
                 :pi-mismatch pi-mismatch
                 :pi-state
                 (attr (identity/current rt (attr pi-run :identity/id))
                       :identity/reservation-state)}))]
        (is (re-find #"invocation" (get-in result [:stale :message])))
        (is (re-find #"scope" (get-in result [:child :message])))
        (is (re-find #"active managed writer"
                     (get-in result [:conflict :message])))
        (is (= "reserved" (:before-state result)))
        (is (nil? (:before-native result)))
        (is (nil? (:attached result)))
        (is (re-find #"already attached"
                     (get-in result [:different :message])))
        (is (= "attached" (:after-state result)))
        (is (= "actual" (:after-native result)))
        (is (re-find #"does not match the launch"
                     (get-in result [:pi-mismatch :message])))
        (is (= "reserved" (:pi-state result)))))))

(deftest provider-finish-late-settlement-and-retry-converge
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [finished-run (harnesses/create!
                                  rt {:harness :codex :mode :headless
                                      :cwd "/tmp/finish"
                                      :prompt "Use {{AGENT_ID}}."
                                      :append-system-prompt
                                      "Current {{AGENT_ID}} / {{RUN_ID}}."})
                    finished-start (harnesses/begin-attempt!
                                    rt (:id finished-run))
                    finished (harnesses/finish!
                              rt (:id finished-run)
                              {:status :done :exit-code 0 :result "done"
                               :session-id "finish-thread"
                               :session-usable true
                               :invocation (:invocation finished-start)})
                    after-run (harnesses/create!
                               rt {:harness :codex :mode :interactive
                                   :cwd "/tmp/finish"
                                   :title "fresh after"
                                   :after (:id finished)})
                    superseded-run (harnesses/create!
                                    rt {:harness :codex :mode :interactive
                                        :cwd "/tmp/superseded"
                                        :title "superseded retry"})
                    superseded-start
                    (harnesses/begin-attempt! rt (:id superseded-run))
                    superseded-run
                    (harnesses/finish!
                     rt (:id superseded-run)
                     {:status :failed :exit-code 1 :error "failed"
                      :invocation (:invocation superseded-start)
                      :evidence {:settled true
                                 :settlement "process-exit"}})
                    _ (harnesses/create!
                       rt {:harness :codex :mode :interactive
                           :cwd "/tmp/superseded"
                           :title "accepted continuation"
                           :after (:id superseded-run)})
                    superseded-retry
                    (failure #(harnesses/retry!
                               rt (:id superseded-run) {}))
                    late-run (harnesses/create!
                              rt {:harness :codex :mode :interactive
                                  :cwd "/tmp/late" :title "late"})
                    late-start (harnesses/begin-attempt! rt (:id late-run))
                    failed (harnesses/finish!
                            rt (:id late-run)
                            {:status :failed :exit-code 1 :error "primary"
                             :invocation (:invocation late-start)
                             :evidence {:settled false
                                        :settlement "no-terminal-evidence"}})
                    missing-fence
                    (failure #(harnesses/settle-outcome!
                               rt (:id failed)
                               {:status :done :exit-code 0
                                :session-id "missing-fence-thread"
                                :session-usable true}
                               {:settled true
                                :settlement "process-exit"}))
                    stale (failure
                           #(harnesses/settle-outcome!
                             rt (:id failed)
                             {:status :done :exit-code 0
                              :session-id "stale-thread"
                              :session-usable true
                              :invocation "stale"}
                             {:settled true :settlement "process-exit"}))
                    settled (harnesses/settle-outcome!
                             rt (:id failed)
                             {:status :done :exit-code 0
                              :session-id "late-thread"
                              :session-usable true
                              :invocation (:invocation late-start)}
                             {:settled true :settlement "process-exit"})
                    pi-run (harnesses/create!
                            rt {:harness :pi :mode :interactive
                                :cwd "/tmp/pi-late"
                                :session-id "pi-pinned"
                                :title "pi late"})
                    pi-start (harnesses/begin-attempt! rt (:id pi-run))
                    pi-failed (harnesses/finish!
                               rt (:id pi-run)
                               {:status :failed :exit-code 1 :error "pi"
                                :invocation (:invocation pi-start)
                                :evidence {:settled false
                                           :settlement "no-terminal-evidence"}})
                    pi-late-mismatch
                    (failure
                     #(harnesses/settle-outcome!
                       rt (:id pi-failed)
                       {:status :done :exit-code 0
                        :session-id "pi-other"
                        :session-usable true
                        :invocation (:invocation pi-start)}
                       {:settled true :settlement "process-exit"}))
                    pi-after (weaver/show rt (:id pi-run))
                    retry-run (harnesses/create!
                               rt {:harness :codex :mode :interactive
                                   :cwd "/tmp/retry"
                                   :title "retry"
                                   :prompt "Hello {{AGENT_ID}}"
                                   :append-system-prompt
                                   "Agent {{AGENT_ID}} run {{RUN_ID}}"})
                    retry-start (harnesses/begin-attempt! rt (:id retry-run))
                    retry-failed (harnesses/finish!
                                  rt (:id retry-run)
                                  {:status :failed :exit-code 1 :error "retry"
                                   :invocation (:invocation retry-start)
                                   :evidence {:settled true
                                              :settlement "process-exit"}})
                    old-identity (attr retry-failed :identity/id)
                    retried (harnesses/retry! rt (:id retry-failed) {})
                    new-identity (attr retried :identity/id)
                    prompt (attr retried :harness/prompt)
                    appends (attr retried :harness/appended-system-prompts)]
                {:finished-session (attr finished :harness/session-id)
                 :finished-attached (attr finished :harness/native-attached)
                 :after-fresh-identity
                 (not= (attr finished :identity/id)
                       (attr after-run :identity/id))
                 :after-fresh-session
                 (not= (attr finished :harness/session-id)
                       (attr after-run :harness/session-id))
                 :after-reserved
                 (attr (identity/current rt (attr after-run :identity/id))
                       :identity/reservation-state)
                 :superseded-retry superseded-retry
                 :late-status (attr settled :harness/status)
                 :late-error (attr settled :harness/error)
                 :late-session (attr settled :harness/session-id)
                 :late-settled (attr settled :harness/settled)
                 :missing-fence missing-fence
                 :stale stale
                 :pi-late-mismatch pi-late-mismatch
                 :pi-settled (attr pi-after :harness/settled)
                 :pi-native-attached (attr pi-after :harness/native-attached)
                 :pi-usable (attr pi-after :harness/session-usable)
                 :retry-old old-identity
                 :retry-new new-identity
                 :retry-session-changed
                 (not= (attr retry-failed :harness/session-id)
                       (attr retried :harness/session-id))
                 :retry-reserved
                 (attr (identity/current rt new-identity)
                       :identity/reservation-state)
                 :prompt prompt
                 :appends appends
                 :run-id (:id retried)}))]
        (is (= "finish-thread" (:finished-session result)))
        (is (= "true" (:finished-attached result)))
        (is (true? (:after-fresh-identity result)))
        (is (true? (:after-fresh-session result)))
        (is (= "reserved" (:after-reserved result)))
        (is (re-find #"already has an accepted continuation"
                     (get-in result [:superseded-retry :message])))
        (is (re-find #"missing or stale invocation"
                     (get-in result [:missing-fence :message])))
        (is (re-find #"stale invocation" (get-in result [:stale :message])))
        (is (= "failed" (:late-status result)))
        (is (= "primary" (:late-error result)))
        (is (= "late-thread" (:late-session result)))
        (is (= "true" (:late-settled result)))
        (is (re-find #"does not match the launch"
                     (get-in result [:pi-late-mismatch :message])))
        (is (= "true" (:pi-settled result)))
        (is (= "false" (:pi-native-attached result)))
        (is (= "false" (:pi-usable result)))
        (is (not= (:retry-old result) (:retry-new result)))
        (is (true? (:retry-session-changed result)))
        (is (= "reserved" (:retry-reserved result)))
        (is (str/includes? (:prompt result) (:retry-new result)))
        (is (not (str/includes? (:prompt result) (:retry-old result))))
        (is (str/includes? (first (:appends result)) (:retry-new result)))
        (is (str/includes? (first (:appends result)) (:run-id result)))))))

(deftest maintenance-providers-and-explicit-legacy-repair-stay-scoped
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [claude (harnesses/create!
                            rt {:harness :claude :mode :interactive
                                :cwd "/tmp/claude" :session-id "claude-native"
                                :title "claude"})
                    legacy
                    (with-redefs [managed/managed-harness? (constantly false)]
                      (let [run (harnesses/create!
                                 rt {:harness :codex :mode :interactive
                                     :cwd "/tmp/legacy"
                                     :session-id "legacy-provisional"
                                     :title "legacy"})
                            started (harnesses/begin-attempt! rt (:id run))]
                        (harnesses/finish!
                         rt (:id run)
                         {:status :done :exit-code 0
                          :session-id "legacy-actual"
                          :session-usable true
                          :invocation (:invocation started)})))
                    legacy-identity (attr legacy :identity/id)
                    repaired (harnesses/repair-managed-startup!
                              rt {:run-id (:id legacy)
                                  :identity legacy-identity
                                  :native-session-id "legacy-actual"})
                    repaired-run (weaver/show rt (:id legacy))
                    repaired-at
                    (attr repaired-run :harness/native-attached-at)
                    repair-replay
                    (harnesses/repair-managed-startup!
                     rt {:run-id (:id legacy)
                         :identity legacy-identity
                         :native-session-id "legacy-actual"})
                    replayed-at
                    (attr (weaver/show rt (:id legacy))
                          :harness/native-attached-at)
                    resumed (harnesses/resume! rt (:id repaired-run) {})
                    occupied (identity/startup!
                              rt {:harness "codex"
                                  :native-session-id "occupied-native"})
                    conflict-legacy
                    (with-redefs [managed/managed-harness? (constantly false)]
                      (let [run (harnesses/create!
                                 rt {:harness :codex :mode :interactive
                                     :cwd "/tmp/conflict"
                                     :session-id "conflict-provisional"
                                     :title "conflict"})
                            started (harnesses/begin-attempt! rt (:id run))]
                        (harnesses/finish!
                         rt (:id run)
                         {:status :done :exit-code 0
                          :session-id "occupied-native"
                          :session-usable true
                          :invocation (:invocation started)})))
                    conflict-identity (attr conflict-legacy :identity/id)
                    before (identity/current rt conflict-identity)
                    conflict (failure
                              #(harnesses/repair-managed-startup!
                                rt {:run-id (:id conflict-legacy)
                                    :identity conflict-identity
                                    :native-session-id "occupied-native"}))
                    after (identity/current rt conflict-identity)]
                {:claude-reservation (attr claude :identity/reservation-id)
                 :claude-native
                 (attr (identity/current rt (attr claude :identity/id))
                       :identity/native-session-id)
                 :claude-bootstrap
                 (harnesses/managed-bootstrap rt (:id claude))
                 :repair-result (:result repaired)
                 :repair-replay (:result repair-replay)
                 :repair-timestamp-stable (= repaired-at replayed-at)
                 :repair-reservation
                 (attr repaired-run :identity/reservation-id)
                 :repair-state
                 (attr (identity/current rt legacy-identity)
                       :identity/reservation-state)
                 :same-resume-identity
                 (= legacy-identity (attr resumed :identity/id))
                 :occupied (:identity occupied)
                 :conflict conflict
                 :conflict-before
                 (attr before :identity/native-session-id)
                 :conflict-after
                 (attr after :identity/native-session-id)}))]
        (is (nil? (:claude-reservation result)))
        (is (= "claude-native" (:claude-native result)))
        (is (nil? (:claude-bootstrap result)))
        (is (= "repaired" (:repair-result result)))
        (is (= "recovered" (:repair-replay result)))
        (is (true? (:repair-timestamp-stable result)))
        (is (string? (:repair-reservation result)))
        (is (= "attached" (:repair-state result)))
        (is (true? (:same-resume-identity result)))
        (is (re-find #"occupied" (get-in result [:conflict :message])))
        (is (= "conflict-provisional" (:conflict-before result)))
        (is (= (:conflict-before result) (:conflict-after result)))))))

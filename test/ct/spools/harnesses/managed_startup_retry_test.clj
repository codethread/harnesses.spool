(ns ct.spools.harnesses.managed-startup-retry-test
  "Managed settlement, retry, and maintenance-provider tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.managed-startup-test :as fixture]))

(deftest provider-finish-late-settlement-and-retry-converge
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
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
                    atomic-late-run
                    (harnesses/create!
                     rt {:harness :codex :mode :interactive
                         :cwd "/tmp/atomic-late"
                         :title "atomic late settlement"})
                    atomic-late-start
                    (harnesses/begin-attempt! rt (:id atomic-late-run))
                    atomic-late-failed
                    (harnesses/finish!
                     rt (:id atomic-late-run)
                     {:status :failed :exit-code 1 :error "primary"
                      :invocation (:invocation atomic-late-start)
                      :evidence {:settled false
                                 :settlement "no-terminal-evidence"}})
                    _ (reset! reject-attachment-batches? true)
                    atomic-late-rejection
                    (failure
                     #(harnesses/settle-outcome!
                       rt (:id atomic-late-failed)
                       {:status :done :exit-code 0
                        :session-id "atomic-late-thread"
                        :session-usable true
                        :invocation (:invocation atomic-late-start)}
                       {:settled true :settlement "process-exit"}))
                    _ (reset! reject-attachment-batches? false)
                    atomic-late-after
                    (weaver/show rt (:id atomic-late-run))
                    atomic-late-identity
                    (identity/current
                     rt (attr atomic-late-run :identity/id))
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
                    retried (harnesses/retry!
                             rt (:id retry-failed)
                             {:by-identity "retry-operator"})
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
                 :atomic-late-rejection atomic-late-rejection
                 :atomic-late-settled
                 (attr atomic-late-after :harness/settled)
                 :atomic-late-settlement
                 (attr atomic-late-after :harness/settlement)
                 :atomic-late-attached
                 (attr atomic-late-after :harness/native-attached)
                 :atomic-late-reservation-state
                 (attr atomic-late-identity :identity/reservation-state)
                 :atomic-late-native
                 (attr atomic-late-identity :identity/native-session-id)
                 :retry-old old-identity
                 :retry-new new-identity
                 :retry-performed-identities
                 (into #{}
                       (map #(attr (weaver/show rt (:from_strand_id %))
                                   :identity/id))
                       (graph/incoming-edges rt [(:id retried)] "performed"))
                 :retry-actors
                 (mapv :by-identity (notes/notes rt (:id retried) {}))
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
        (is (re-find #"durable pin"
                     (get-in result [:pi-late-mismatch :message])))
        (is (= "true" (:pi-settled result)))
        (is (= "false" (:pi-native-attached result)))
        (is (= "false" (:pi-usable result)))
        (is (some? (:atomic-late-rejection result)))
        (is (= "true" (:atomic-late-settled result)))
        (is (= "process-exit" (:atomic-late-settlement result)))
        (is (= "false" (:atomic-late-attached result)))
        (is (= "reserved" (:atomic-late-reservation-state result)))
        (is (nil? (:atomic-late-native result)))
        (is (not= (:retry-old result) (:retry-new result)))
        (is (= #{(:retry-old result) (:retry-new result)}
               (:retry-performed-identities result)))
        (is (= ["retry-operator"] (:retry-actors result)))
        (is (true? (:retry-session-changed result)))
        (is (= "reserved" (:retry-reserved result)))
        (is (str/includes? (:prompt result) (:retry-new result)))
        (is (not (str/includes? (:prompt result) (:retry-old result))))
        (is (str/includes? (first (:appends result)) (:retry-new result)))
        (is (str/includes? (first (:appends result)) (:run-id result)))))))

(deftest native-resume-retry-retains-frozen-settings-and-guidance
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [_ (harnesses/register-alias!
                       rt :frozen-codex
                       {:doc "Frozen retry alias."
                        :parent :codex
                        :env {"FROZEN_SETTING" "old"}
                        :attributes
                        {:harness/model "old-model"
                         :harness/appended-system-prompts
                         ["Old instruction for {{AGENT_ID}} / {{RUN_ID}}."]}})
                    root (harnesses/create!
                          rt {:harness :frozen-codex
                              :mode :interactive
                              :cwd "/tmp/frozen-retry"
                              :title "frozen root"})
                    root-start (harnesses/begin-attempt! rt (:id root))
                    _ (harnesses/managed-startup!
                       rt {:harness "codex"
                           :native-session-id "frozen-thread"
                           :cwd "/tmp/frozen-retry"
                           :scope "root"
                           :bootstrap
                           (harnesses/managed-bootstrap rt (:id root))})
                    root (harnesses/finish!
                          rt (:id root)
                          {:status :done :exit-code 0
                           :invocation (:invocation root-start)})
                    resumed (harnesses/resume! rt (:id root) {})
                    resumed-start
                    (harnesses/begin-attempt! rt (:id resumed))
                    _ (harnesses/managed-startup!
                       rt {:harness "codex"
                           :native-session-id "frozen-thread"
                           :cwd "/tmp/frozen-retry"
                           :scope "root"
                           :bootstrap
                           (harnesses/managed-bootstrap rt (:id resumed))})
                    failed (harnesses/finish!
                            rt (:id resumed)
                            {:status :failed :exit-code 1
                             :error "retry me"
                             :invocation (:invocation resumed-start)
                             :evidence {:settled true
                                        :settlement "process-exit"}})
                    frozen-identity (attr failed :identity/id)
                    frozen-session (attr failed :harness/session-id)
                    _ (harnesses/unregister-alias! rt :frozen-codex)
                    _ (harnesses/register-alias!
                       rt :frozen-codex
                       {:doc "Incompatible live replacement."
                        :parent :pi
                        :env {"FROZEN_SETTING" "new"}
                        :attributes
                        {:harness/model "new-model"
                         :harness/appended-system-prompts
                         ["New live instruction."]}})
                    before-rejections (weaver/show rt (:id failed))
                    alias-replacement
                    (failure #(harnesses/retry!
                               rt (:id failed) {:harness :frozen-codex}))
                    cwd-replacement
                    (failure #(harnesses/retry!
                               rt (:id failed) {:cwd "/tmp/changed"}))
                    settings-replacement
                    (failure #(harnesses/retry!
                               rt (:id failed)
                               {:attributes {:harness/model "new-model"}}))
                    after-rejections (weaver/show rt (:id failed))
                    _ (harnesses/unregister-alias! rt :frozen-codex)
                    retried (harnesses/retry! rt (:id failed) {})]
                {:alias-replacement alias-replacement
                 :cwd-replacement cwd-replacement
                 :settings-replacement settings-replacement
                 :rejections-no-write
                 (= before-rejections after-rejections)
                 :same-identity
                 (= frozen-identity (attr retried :identity/id))
                 :same-session
                 (= frozen-session (attr retried :harness/session-id))
                 :cwd (attr retried :harness/cwd)
                 :model (attr retried :harness/model)
                 :env (attr retried :harness/env)
                 :guidance
                 (attr retried :harness/appended-system-prompts)
                 :run-id (:id retried)
                 :identity (attr retried :identity/id)}))]
        (is (re-find #"cannot replace its frozen provider"
                     (get-in result [:alias-replacement :message])))
        (is (re-find #"cannot change frozen cwd"
                     (get-in result [:cwd-replacement :message])))
        (is (re-find #"cannot change frozen provider settings"
                     (get-in result [:settings-replacement :message])))
        (is (true? (:rejections-no-write result)))
        (is (true? (:same-identity result)))
        (is (true? (:same-session result)))
        (is (= "/tmp/frozen-retry" (:cwd result)))
        (is (= "old-model" (:model result)))
        (is (= {:FROZEN_SETTING "old"} (:env result)))
        (is (= [(str "Old instruction for " (:identity result)
                     " / " (:run-id result) ".")]
               (:guidance result)))))))

(deftest maintenance-providers-and-explicit-legacy-repair-stay-scoped
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
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
                    repair-before
                    [(weaver/show rt (:id legacy))
                     (identity/current rt legacy-identity)
                     (targets (identity/current rt legacy-identity)
                              "performed")]
                    _ (reset! reject-attachment-batches? true)
                    repair-failure
                    (failure #(harnesses/repair-managed-startup!
                               rt {:run-id (:id legacy)
                                   :identity legacy-identity
                                   :native-session-id "legacy-actual"}))
                    _ (reset! reject-attachment-batches? false)
                    repair-after
                    [(weaver/show rt (:id legacy))
                     (identity/current rt legacy-identity)
                     (targets (identity/current rt legacy-identity)
                              "performed")]
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
                 :repair-failure repair-failure
                 :repair-unchanged (= repair-before repair-after)
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
        (is (some? (:repair-failure result)))
        (is (true? (:repair-unchanged result)))
        (is (= "repaired" (:repair-result result)))
        (is (= "recovered" (:repair-replay result)))
        (is (true? (:repair-timestamp-stable result)))
        (is (string? (:repair-reservation result)))
        (is (= "attached" (:repair-state result)))
        (is (true? (:same-resume-identity result)))
        (is (re-find #"occupied" (get-in result [:conflict :message])))
        (is (= "conflict-provisional" (:conflict-before result)))
        (is (= (:conflict-before result) (:conflict-after result)))))))

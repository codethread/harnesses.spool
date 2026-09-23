(ns ct.spools.harnesses.spool-test
  "Authoring and activation tests for the consolidated Harnesses spool."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.agent-bin :as agent-bin]
            [ct.spools.harnesses.agent-cli :as agent-cli]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.cli :as cli]
            [ct.spools.harnesses.internal.process-custody :as custody]
            [ct.spools.harnesses.process-custody :as process-custody]
            [ct.spools.harnesses.reconciliation :as reconciliation]
            [ct.spools.harnesses.providers.claude :as claude]
            [ct.spools.harnesses.providers.codex :as codex]
            [ct.spools.harnesses.providers.cursor :as cursor]
            [ct.spools.harnesses.providers.pi :as pi]
            [millstrand.test.alpha :as test-alpha]))

(deftest provider-namespaces-export-inert-declarations
  (testing "lifecycle declarations are ordinary importable values"
    (doseq [declaration [harnesses/harness-core-runtime
                         claude/claude-harness-runtime
                         codex/codex-harness-runtime
                         cursor/cursor-harness-runtime
                         pi/pi-harness-runtime
                         execution/harness-execution-runtime]]
      (is (= :resource (:kind declaration))))
    (doseq [declaration [process-custody/harness-process-custody
                         reconciliation/interactive-reconciliation-sweep]]
      (is (= :reconcile (:kind declaration)))))
  (testing "core registry forms carry reusable authoring descriptors"
    (doseq [declaration-var [#'agent-cli/agent
                             #'execution/on-event
                             #'agent-bin/agent]]
      (is (map? (:millstrand.api.authoring.alpha/declaration
                 (meta declaration-var)))))))

(deftest every-agent-command-accepts-caller-identity
  (is (not (contains? (:subcommands cli/agent-arg-spec) "await")))
  (doseq [path [["assign"] ["reviewers"] ["review"] ["run"] ["show"]
                ["runs"] ["stop"] ["reconcile"] ["retry"] ["resumable"]
                ["resume"] ["self-complete"] ["list"]]]
    (is (contains? (get-in cli/agent-arg-spec
                           (into [:subcommands]
                                 (mapcat #(vector % :subcommands) (butlast path))))
                   (last path)))
    (is (contains? (get-in cli/agent-arg-spec
                           (into [:subcommands]
                                 (concat
                                  (mapcat #(vector % :subcommands) (butlast path))
                                  [(last path) :flags])))
                   :by-identity)))
  (doseq [path [["startup"] ["repair-startup"] ["_callback-contract"]
                ["_started"] ["_provider_started"] ["_finished"]
                ["config" "list"] ["config" "set"] ["config" "unset"]]]
    (is (not (contains? (or (get-in cli/agent-arg-spec
                                    (into [:subcommands]
                                          (concat
                                           (mapcat #(vector % :subcommands)
                                                   (butlast path))
                                           [(last path) :flags])))
                            {})
                        :by-identity)))))

(deftest pending-custody-adoption-is-owner-key-exact
  (let [run {:id "run-a"
             :attributes {:harness/process-owner "agent-harness/run"
                          :harness/process-key "run-a/attempt-1"
                          :harness/process-handle "pending"
                          :harness/attempt 1}}
        retained {:owner custody/owner
                  :key "run-a/attempt-1"
                  :handle "opaque-a"
                  :phase :running}]
    (is (= retained (custody/record-for "harness" run [retained])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing for an active run"
                          (custody/record-for "harness" run [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"multiple retained facts"
                          (custody/record-for
                           "harness" run
                           [retained (assoc retained :handle "opaque-b")])))))

(deftest bundled-selector-publishes-complete-harness-surface
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    (test-alpha/with-weaver-world
      [ctx {:storage :sqlite-memory
            :deps-edn
            (pr-str
             {:deps
              {'ct.spools/harnesses
               {:local/root (.getCanonicalPath harnesses-root)}
               'millhouse.spools/identity
               {:local/root (.getCanonicalPath identity-root)}}})
            :init-clj
            "(require '[millstrand.api.current.alpha :as current]
                       '[millstrand.api.runtime.alpha :as runtime])
             (def rt (current/runtime))
             (runtime/module! rt :identity
               {:ns 'millhouse.spools.identity
                :required? true})
             (runtime/module! rt :harnesses
               {:ns 'ct.spools.harnesses.spool
                :after [:identity]
                :required? true})"}]
      (let [{:keys [harnesses operation handler bins lifecycles queries]}
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.events.alpha :as events]
                         '[millstrand.api.graph.alpha :as graph]
                         '[millstrand.api.runtime.alpha :as runtime]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)]
                  {:harnesses (mapv :name (harnesses/harnesses rt))
                   :operation (:name (weaver/resolve-op rt 'agent))
                   :handler (some #(when (= :on-event (:key %)) %)
                                  (events/handlers rt))
                   :bins (set (map :name (:bins (weaver/op! rt 'bins ["list"]))))
                   :queries (set (keys (graph/queries rt)))
                   :lifecycles (get-in (runtime/status rt)
                                       [:last-refresh :modules :harnesses
                                        :lifecycle/outcomes])})))]
        (is (= ["claude" "codex" "cursor" "pi"] harnesses))
        (is (= "agent" operation))
        (is (contains? bins "agent"))
        (doseq [query ["agent-run-terminal" "agent-run-settled"
                       "agent-run-active" "agent-runs-active"
                       "agent-runs-for-target" "agent-work-complete"
                       "agent-work-complete-or-intervention"
                       "agent-work-root-complete"
                       "agent-work-root-complete-or-intervention"]]
          (is (contains? queries query)))
        (is (= #{:strand/added :strand/updated :batch/applied :strand/burned}
               (:types handler)))
        (doseq [effect [:harness-core-runtime
                        :claude-harness-runtime
                        :codex-harness-runtime
                        :cursor-harness-runtime
                        :pi-harness-runtime
                        :harness-execution-runtime
                        :harness-process-custody
                        :interactive-reconciliation-sweep]]
          (is (= :applied (get-in lifecycles [effect :status]))))
        (testing "run flags override effort and append a system prompt"
          (is (= [["adaptive" ["Review without editing."]]
                  ["maximum" ["Review without editing."]]]
                 (test-alpha/repl!
                  ctx
                  '(let [rt (millstrand.api.current.alpha/runtime)]
                     (mapv
                      (fn [flag effort]
                        (let [created (millstrand.api.weaver.alpha/op!
                                       rt 'agent
                                       ["run" "pi" "--interactive"
                                        "--cwd" "/tmp" flag effort
                                        "--append-system-prompt"
                                        "Review without editing."])
                              run (millstrand.api.weaver.alpha/show
                                   rt (:id created))]
                          [(millstrand.api.spool.alpha/attr-get
                            run :harness/effort)
                           (millstrand.api.spool.alpha/attr-get
                            run :harness/appended-system-prompts)]))
                      ["--effort" "--thinking"]
                      ["adaptive" "maximum"]))))))
        (testing "run provider argv uses ordinary caller overlay precedence"
          (is (= {:generated ["--from-alias"]
                  :overrides ["--provider-flag" "value with spaces" "" "--"
                              ":stdin" ":payload/example"
                              "Keep {{RUN_ID}} and {{AGENT_ID}} literal"]
                  :effective ["--provider-flag" "value with spaces" "" "--"
                              ":stdin" ":payload/example"
                              "Keep {{RUN_ID}} and {{AGENT_ID}} literal"]
                  :resumed ["--provider-flag" "value with spaces" "" "--"
                            ":stdin" ":payload/example"
                            "Keep {{RUN_ID}} and {{AGENT_ID}} literal"]
                  :retried ["--one" "{{RUN_ID}}" "{{AGENT_ID}}"
                            ":stdin" ":payload/example"]
                  :launcher true}
                 (test-alpha/repl!
                  ctx
                  '(let [rt (millstrand.api.current.alpha/runtime)
                         _ (harnesses/register-alias!
                            rt :cli-tail
                            {:doc "Exercise provider argv forwarding."
                             :parent :pi
                             :attributes
                             {:harness/extra-argv ["--from-alias"]}})
                         created
                         (millstrand.api.weaver.alpha/op!
                          rt 'agent
                          ["run" "cli-tail" "--interactive" "--cwd" "/tmp"
                           "--extra-argv" "=--provider-flag"
                           "--extra-argv" "=value with spaces"
                           "--extra-argv" "="
                           "--extra-argv" "=--"
                           "--extra-argv" "=:stdin"
                           "--extra-argv" "=:payload/example"
                           "--extra-argv"
                           "=Keep {{RUN_ID}} and {{AGENT_ID}} literal"])
                         run (millstrand.api.weaver.alpha/show rt (:id created))
                         started (harnesses/begin-attempt! rt (:id run))
                         _ (harnesses/register-native-session!
                            rt {:harness "pi"
                                :native-session-id
                                (millstrand.api.spool.alpha/attr-get
                                 run :harness/session-id)
                                :cwd "/tmp"
                                :run-id (:id run)})
                         _ (harnesses/finish!
                            rt (:id run)
                            {:status :done
                             :exit-code 0
                             :session-id
                             (millstrand.api.spool.alpha/attr-get
                              run :harness/session-id)
                             :session-usable true
                             :invocation (:invocation started)})
                         resumed (harnesses/resume! rt (:id run) {})
                         retry-created
                         (millstrand.api.weaver.alpha/op!
                          rt 'agent
                          ["run" "pi" "--interactive" "--cwd" "/tmp"
                           "--extra-argv" "=--one"
                           "--extra-argv" "={{RUN_ID}}"
                           "--extra-argv" "={{AGENT_ID}}"
                           "--extra-argv" "=:stdin"
                           "--extra-argv" "=:payload/example"])
                         retry-run
                         (millstrand.api.weaver.alpha/show
                          rt (:id retry-created))
                         retry-start
                         (harnesses/begin-attempt! rt (:id retry-run))
                         _ (harnesses/register-native-session!
                            rt {:harness "pi"
                                :native-session-id
                                (millstrand.api.spool.alpha/attr-get
                                 retry-run :harness/session-id)
                                :cwd "/tmp"
                                :run-id (:id retry-run)})
                         retry-failed
                         (harnesses/finish!
                          rt (:id retry-run)
                          {:status :failed
                           :exit-code 1
                           :error "retry literal argv"
                           :invocation (:invocation retry-start)
                           :evidence {:settled true
                                      :settlement "process-exit"}})
                         retried
                         (harnesses/retry! rt (:id retry-failed) {})]
                     {:generated
                      (get-in run [:attributes :harness/generated
                                   :harness/extra-argv])
                      :overrides
                      (get-in run [:attributes :harness/overrides
                                   :harness/extra-argv])
                      :effective
                      (millstrand.api.spool.alpha/attr-get
                       run :harness/extra-argv)
                      :resumed
                      (millstrand.api.spool.alpha/attr-get
                       resumed :harness/extra-argv)
                      :retried
                      (millstrand.api.spool.alpha/attr-get
                       retried :harness/extra-argv)
                      :launcher
                      (let [script (slurp (:launcher created))]
                        (and
                         (clojure.string/includes?
                          script
                          "'--provider-flag' 'value with spaces' '' '--'")
                         (clojure.string/includes?
                          script
                          "':stdin' ':payload/example'")
                         (clojure.string/includes?
                          script
                          "'Keep {{RUN_ID}} and {{AGENT_ID}} literal'")
                         (clojure.string/includes?
                          script "agent _provider_started")
                         (clojure.string/includes?
                          script "--provider-pid \"$$\"")))})))))
        (testing "legacy callbacks remain completable by the current backend"
          (is (= {:contract 2
                  :owner-recorded false
                  :provider-fenced true
                  :status "failed"
                  :settled true
                  :legacy-launcher-path true}
                 (test-alpha/repl!
                  ctx
                  '(let [rt (millstrand.api.current.alpha/runtime)
                         created
                         (millstrand.api.weaver.alpha/op!
                          rt 'agent
                          ["run" "pi" "--interactive" "--cwd" "/tmp"])
                         started
                         (millstrand.api.weaver.alpha/op!
                          rt 'agent ["_started" (:id created)])
                         _
                         (millstrand.api.weaver.alpha/op!
                          rt 'agent
                          ["_provider_started" (:id created)
                           "--provider-pid"
                           (str (.pid (java.lang.ProcessHandle/current)))])
                         finished
                         (millstrand.api.weaver.alpha/op!
                          rt 'agent
                          ["_finished" (:id created) "--exit-code" "1"])
                         stored
                         (millstrand.api.weaver.alpha/show rt (:id created))
                         script (slurp (:launcher created))]
                     {:contract
                      (:version
                       (millstrand.api.weaver.alpha/op!
                        rt 'agent ["_callback-contract"]))
                      :owner-recorded
                      (some? (millstrand.api.spool.alpha/attr-get
                              stored :harness/completion-owner-pid))
                      :provider-fenced
                      (= (:invocation started)
                         (millstrand.api.spool.alpha/attr-get
                          stored :harness/provider-invocation))
                      :status (:status finished)
                      :settled (:settled finished)
                      :legacy-launcher-path
                      (and
                       (clojure.string/includes?
                        script "${_MILLSTRAND_HARNESS_INVOCATION:-}")
                       (clojure.string/includes? script "else\n  strand"))})))))
        (testing "configured provider argv keeps invocation templating"
          (is (true?
               (test-alpha/repl!
                ctx
                '(let [rt (millstrand.api.current.alpha/runtime)
                       _ (harnesses/register-alias!
                          rt :configured-markers
                          {:doc "Exercise configured argv templating."
                           :parent :pi
                           :attributes
                           {:harness/extra-argv
                            ["Configured {{RUN_ID}} and {{AGENT_ID}}"]}})
                       created
                       (millstrand.api.weaver.alpha/op!
                        rt 'agent
                        ["run" "configured-markers" "--interactive"
                         "--cwd" "/tmp"])
                       run (millstrand.api.weaver.alpha/show rt (:id created))
                       identity
                       (millstrand.api.spool.alpha/attr-get run :identity/id)
                       expected (str "Configured " (:id run) " and {{AGENT_ID}}")]
                   (and
                    (= [expected]
                       (millstrand.api.spool.alpha/attr-get
                        run :harness/extra-argv))
                    (clojure.string/includes?
                     (slurp (:launcher created))
                     (str "'" expected "'"))))))))
        (testing "aliases expose documentation, model, and open effort"
          (is (= {:registration
                  {:alias "reviewer"
                   :candidates
                   [{:doc "Review with high effort."
                     :parent "terra"
                     :model "reviewer-model"
                     :effort :max
                     :append-system-prompt "Do not edit files."
                     :env {"CLAUDE_CONFIG_DIR" "~/.config/reviewer"
                           "REVIEW_MODE" "strict"}
                     :attributes {}}]}
                  :listing
                  {:name "reviewer"
                   :kind "alias"
                   :candidates
                   [{:doc "Review with high effort."
                     :parent "terra"
                     :model "reviewer-model"
                     :effort :max
                     :append-system-prompt "Do not edit files."
                     :env {"CLAUDE_CONFIG_DIR" "~/.config/reviewer"
                           "REVIEW_MODE" "strict"}
                     :attributes {}}]
                   :available true
                   :harness "pi"
                   :selected-candidate 0
                   :selected-parent "terra"}
                  :portable
                  {:harness/extra-argv []
                   :harness/model "terra-model"
                   :harness/effort "high"
                   :harness/appended-system-prompts
                   ["Review changes only."]}
                  :portable-env
                  {"CLAUDE_CONFIG_DIR" "~/.config/claude"}
                  :generated
                  {:harness/extra-argv []
                   :harness/model "reviewer-model"
                   :harness/effort "max"
                   :harness/appended-system-prompts
                   ["Review changes only." "Do not edit files."]}
                  :env
                  {"CLAUDE_CONFIG_DIR" "~/.config/reviewer"
                   "REVIEW_MODE" "strict"}
                  :launcher-env true}
                 (test-alpha/repl!
                  ctx
                  '(do
                     (require '[ct.spools.harnesses :as harnesses]
                              '[millstrand.api.current.alpha :as current])
                     (let [rt (current/runtime)
                           _ (harnesses/register-alias!
                              rt :terra
                              {:doc "Use Terra."
                               :parent :pi
                               :model "terra-model"
                               :effort :high
                               :append-system-prompt "Review changes only."
                               :env {"CLAUDE_CONFIG_DIR" "~/.config/claude"}
                               :attributes {}})
                           registration
                           (harnesses/register-alias!
                            rt :reviewer
                            {:doc "Review with high effort."
                             :parent :terra
                             :model "reviewer-model"
                             :effort :max
                             :append-system-prompt "Do not edit files."
                             :env {"CLAUDE_CONFIG_DIR" "~/.config/reviewer"
                                   "REVIEW_MODE" "strict"}
                             :attributes {}})
                           portable (harnesses/resolve-harness rt :terra)
                           resolved (harnesses/resolve-harness rt :reviewer)
                           run (harnesses/create!
                                rt {:harness :reviewer :mode :interactive})
                           launcher (ct.spools.harnesses.execution/prepare-interactive!
                                     rt run)
                           script (slurp launcher)]
                       {:registration registration
                        :listing (some #(when (= "reviewer" (:name %)) %)
                                       (harnesses/harnesses rt))
                        :portable (:generated portable)
                        :portable-env (:env portable)
                        :generated (:generated resolved)
                        :env (:env resolved)
                        :launcher-env
                        (and (clojure.string/includes?
                              script
                              "export CLAUDE_CONFIG_DIR='~/.config/reviewer'")
                             (clojure.string/includes?
                              script
                              "export REVIEW_MODE='strict'"))}))))))
        (testing "list applies the caller alias visibility policy"
          (is (= {:allow ["oracle" "reviewer"]
                  :deny-targets #{}
                  :deny-keeps-pi true
                  :unknown []
                  :ambiguous []
                  :read-count-stable true
                  :plain-list-vector true
                  :conflict-rejected true}
                 (test-alpha/repl!
                  ctx
                  '(let [rt (millstrand.api.current.alpha/runtime)
                         _ (harnesses/register-alias!
                            rt :oracle
                            {:doc "Use the reviewer."
                             :parent :reviewer
                             :attributes {}})
                         _ (harnesses/register-alias!
                            rt :allow-seat
                            {:doc "See reviewer descendants only."
                             :parent :pi
                             :allow #{:reviewer}
                             :attributes {}})
                         _ (harnesses/register-alias!
                            rt :deny-seat
                            {:doc "Hide Terra descendants."
                             :parent :pi
                             :deny #{:terra}
                             :attributes {}})
                         listing
                         (fn [alias]
                           (let [run (harnesses/create!
                                      rt {:harness alias :mode :interactive})
                                 _ (harnesses/begin-attempt! rt (:id run))
                                 friendly-id
                                 (:identity (harnesses/register-native-session!
                                             rt {:harness "pi" :run-id (:id run)
                                                 :native-session-id (millstrand.api.spool.alpha/attr-get run :harness/session-id)
                                                 :cwd (millstrand.api.spool.alpha/attr-get run :harness/cwd)}))]
                             (millstrand.api.weaver.alpha/op!
                              rt 'agent
                              ["list" "--by-identity" friendly-id])))
                         allowed (listing :allow-seat)
                         denied (listing :deny-seat)
                         _ (doseq [native ["duplicate-a" "duplicate-b"]]
                             (weaver/add!
                              rt
                              {:title "duplicate-list-actor"
                               :attributes
                               {:identity/session "true"
                                :identity/id "duplicate-list-actor"
                                :identity/harness "pi"
                                :identity/native-session-id native}}))
                         before-read-count (count (weaver/list rt))
                         unknown-list
                         (weaver/op! rt 'agent
                                     ["list" "--by-identity"
                                      "unknown-list-actor"])
                         ambiguous-list
                         (weaver/op! rt 'agent
                                     ["list" "--by-identity"
                                      "duplicate-list-actor"])
                         after-read-count (count (weaver/list rt))]
                     {:allow (mapv :name allowed)
                      :deny-targets
                      (into #{}
                            (filter #{"terra" "reviewer" "oracle"})
                            (map :name denied))
                      :deny-keeps-pi
                      (contains? (set (map :name denied)) "pi")
                      :unknown unknown-list
                      :ambiguous ambiguous-list
                      :read-count-stable (= before-read-count after-read-count)
                      :plain-list-vector
                      (vector? (millstrand.api.weaver.alpha/op!
                                rt 'agent ["list"]))
                      :conflict-rejected
                      (try
                        (harnesses/register-alias!
                         rt :invalid-visibility
                         {:doc "Invalid."
                          :parent :pi
                          :allow #{:reviewer}
                          :deny #{:oracle}
                          :attributes {}})
                        false
                        (catch clojure.lang.ExceptionInfo _ true))})))))
        (testing "nested agent calls separate actor and worker provenance"
          (is (= {:origin-attributed-child-run true
                  :child-attributed-descendant-runs true
                  :caller-is-not-native-parent true
                  :origin-performed-run true
                  :child-performed-run true
                  :grandchild-performed-runs true
                  :resume-to-predecessor true}
                 (test-alpha/repl!
                  ctx
                  '(do
                     (require '[millhouse.spools.identity :as identity]
                              '[millstrand.api.graph.alpha :as graph]
                              '[millstrand.api.spool.alpha :as spool]
                              '[millstrand.api.weaver.alpha :as weaver])
                     (let [rt (millstrand.api.current.alpha/runtime)
                           native! (fn [summary]
                                     (harnesses/begin-attempt! rt (:id summary))
                                     (assoc summary :identity
                                            (:identity (harnesses/register-native-session!
                                                        rt {:harness "pi" :run-id (:id summary)
                                                            :cwd "/tmp" :native-session-id (:session-id summary)}))))
                           launch #(weaver/op! rt 'agent
                                               ["run" "reviewer" "--interactive"
                                                "--cwd" "/tmp"])
                           origin-run (native! (launch))
                           origin-id (:identity origin-run)
                           child-run (weaver/op!
                                      rt 'agent
                                      ["run" "reviewer" "--interactive"
                                       "--cwd" "/tmp"
                                       "--by-identity" origin-id])
                           child-run (native! child-run)
                           child-id (:identity child-run)
                           grandchild-run
                           (weaver/op!
                            rt 'agent
                            ["run" "reviewer" "--interactive"
                             "--cwd" "/tmp"
                             "--by-identity" child-id])
                           grandchild-run (native! grandchild-run)
                           grandchild-id (:identity grandchild-run)
                           grandchild-start (weaver/show rt (:id grandchild-run))
                           _ (harnesses/finish!
                              rt (:id grandchild-run)
                              {:status :done :exit-code 0
                               :session-id (:session-id grandchild-run)
                               :session-usable true
                               :invocation (spool/attr-get grandchild-start :harness/invocation)})
                           resumed-run
                           (weaver/op!
                            rt 'agent
                            ["resume" "--run-id" (:id grandchild-run)
                             "--interactive" "--by-identity" child-id])
                           resumed-run (native! resumed-run)
                           identity-strand #(identity/current rt %)
                           _ (identity/reconcile-attributions!
                              rt [(:id child-run) (:id grandchild-run)
                                  (:id resumed-run)])
                           target-ids #(into #{}
                                             (map :to_strand_id)
                                             (graph/outgoing-edges
                                              rt [(:id (identity-strand %))] %2))]
                       {:origin-attributed-child-run
                        (= #{(:id child-run)}
                           (target-ids origin-id "attributed"))
                        :child-attributed-descendant-runs
                        (= #{(:id grandchild-run) (:id resumed-run)}
                           (target-ids child-id "attributed"))
                        :caller-is-not-native-parent
                        (and (empty? (target-ids origin-id "parent-of"))
                             (empty? (target-ids child-id "parent-of")))
                        :origin-performed-run
                        (= #{(:id origin-run)}
                           (target-ids origin-id "performed"))
                        :child-performed-run
                        (= #{(:id child-run)}
                           (target-ids child-id "performed"))
                        :grandchild-performed-runs
                        (= #{(:id grandchild-run) (:id resumed-run)}
                           (target-ids grandchild-id "performed"))
                        :resume-to-predecessor
                        (= #{(:id grandchild-run)}
                           (into #{}
                                 (map :to_strand_id)
                                 (graph/outgoing-edges
                                  rt [(:id resumed-run)] "resumes")))}))))))
        (testing "ordered definitions follow flags and provider availability"
          (is (= {:initial ["pi" "maximum"]
                  :preferred ["claude" "high"]
                  :fallback ["pi" "maximum"]
                  :unavailable false
                  :re-enabled "pi"
                  :flag false}
                 (test-alpha/repl!
                  ctx
                  '(let [rt (millstrand.api.current.alpha/runtime)
                         _ (harnesses/register-alias!
                            rt :conditional-fable
                            {:doc "Conditional Fable."
                             :parent :claude
                             :when :seat/fable
                             :effort :high
                             :attributes {}})
                         _ (harnesses/register-alias!
                            rt :fallback-oracle
                            [{:doc "Use Fable."
                              :parent :conditional-fable
                              :effort :high
                              :attributes {}}
                             {:doc "Use Pi."
                              :parent :pi
                              :effort :maximum
                              :attributes {}}])
                         resolve #(let [resolved (harnesses/resolve-harness
                                                  rt :fallback-oracle)]
                                    [(:harness resolved)
                                     (get-in resolved
                                             [:generated :harness/effort])])
                         initial (resolve)
                         _ (harnesses/set-flag! rt :seat/fable true)
                         preferred (resolve)
                         _ (millstrand.api.weaver.alpha/op!
                            rt 'agent ["config" "set"
                                       "harness/claude" "false"])
                         fallback (resolve)
                         _ (harnesses/set-flag! rt :harness/pi false)
                         unavailable (:available
                                      (some #(when (= "fallback-oracle"
                                                      (:name %))
                                               %)
                                            (harnesses/harnesses rt)))
                         _ (millstrand.api.weaver.alpha/op!
                            rt 'agent ["config" "set"
                                       "harness/pi" "true"])
                         re-enabled (:harness
                                     (harnesses/resolve-harness
                                      rt :fallback-oracle))
                         flag (get-in (millstrand.api.weaver.alpha/op!
                                       rt 'agent ["config" "list"])
                                      [:flags "harness/claude"])]
                     {:initial initial
                      :preferred preferred
                      :fallback fallback
                      :unavailable unavailable
                      :re-enabled re-enabled
                      :flag flag})))))
        (testing "the runtime resource can close and reopen in one Weaver"
          (is (= [{:closed :harness-execution}
                  {:opened :harness-execution :claimed []}]
                 (test-alpha/repl!
                  ctx
                  '(do
                     (require '[ct.spools.harnesses.execution :as execution]
                              '[millstrand.api.current.alpha :as current])
                     (let [context {:runtime (current/runtime)}]
                       [(execution/close-execution! context)
                        (execution/open-execution! context)])))))
          (testing "a failed open releases its state for lifecycle retry"
            (is (= ["forced open failure"
                    {:opened :harness-execution :claimed []}]
                   (test-alpha/repl!
                    ctx
                    '(do
                       (require '[ct.spools.harnesses :as harnesses]
                                '[ct.spools.harnesses.execution :as execution]
                                '[millstrand.api.current.alpha :as current])
                       (let [context {:runtime (current/runtime)}
                             migrate-var #'harnesses/migrate-runs!]
                         (execution/close-execution! context)
                         [(with-redefs-fn
                            {migrate-var
                             (fn [_runtime]
                               (throw (ex-info "forced open failure" {})))}
                            #(try
                               (execution/open-execution! context)
                               nil
                               (catch clojure.lang.ExceptionInfo error
                                 (ex-message error))))
                          (execution/open-execution! context)])))))
            (testing "core continuation selectors resolve the latest completed run"
              (let [{:keys [second-id by-run by-session by-identity]}
                    (test-alpha/repl!
                     ctx
                     '(do
                        (require '[ct.spools.harnesses :as harnesses]
                                 '[millstrand.api.current.alpha :as current])
                        (let [rt (current/runtime)
                              _ (harnesses/register-harness!
                                 rt :fake
                                 {:modes #{:interactive}
                                  :prepare 'ct.spools.harnesses/create!
                                  :finish 'ct.spools.harnesses/finish!})
                              first-run (harnesses/create!
                                         rt {:harness :fake :mode :interactive})
                              first-run (harnesses/finish!
                                         rt (:id first-run)
                                         {:status :done :exit-code 0
                                          :session-usable true})
                              second-run (harnesses/resume! rt (:id first-run) {})
                              second-run (harnesses/finish!
                                          rt (:id second-run)
                                          {:status :done :exit-code 0
                                           :session-usable true})
                              session-id (get-in second-run
                                                 [:attributes :harness/session-id])
                              identity (get-in second-run [:attributes :identity/id])]
                          {:first-id (:id first-run)
                           :second-id (:id second-run)
                           :by-run (:id (harnesses/resolve-resume-run
                                         rt {:run-id (:id second-run)}))
                           :by-session (:id (harnesses/resolve-resume-run
                                             rt {:session-id session-id}))
                           :by-identity (:id (harnesses/resolve-resume-run
                                              rt {:identity identity}))})))]
                (is (= second-id by-run by-session by-identity))))))))))

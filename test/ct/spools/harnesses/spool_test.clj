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
    (is (= :reconcile (:kind process-custody/harness-process-custody))))
  (testing "core registry forms carry reusable authoring descriptors"
    (doseq [declaration-var [#'agent-cli/agent
                             #'execution/on-event
                             #'agent-bin/agent]]
      (is (map? (:millstrand.api.authoring.alpha/declaration
                 (meta declaration-var)))))))

(deftest every-agent-command-accepts-caller-identity
  (doseq [path [["run"] ["retry"] ["resumable"] ["resume"]
                ["self-complete"] ["list"]]]
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
  (doseq [path [["await"] ["_started"] ["_finished"] ["config" "list"]
                ["config" "set"] ["config" "unset"]]]
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
      (let [{:keys [harnesses operation handler bins lifecycles]}
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[millstrand.api.current.alpha :as current]
                         '[millstrand.api.events.alpha :as events]
                         '[millstrand.api.runtime.alpha :as runtime]
                         '[millstrand.api.weaver.alpha :as weaver])
                (let [rt (current/runtime)]
                  {:harnesses (mapv :name (harnesses/harnesses rt))
                   :operation (:name (weaver/resolve-op rt 'agent))
                   :handler (some #(when (= :on-event (:key %)) %)
                                  (events/handlers rt))
                   :bins (set (map :name (:bins (weaver/op! rt 'bins ["list"]))))
                   :lifecycles (get-in (runtime/status rt)
                                       [:last-refresh :modules :harnesses
                                        :lifecycle/outcomes])})))]
        (is (= ["claude" "codex" "cursor" "pi"] harnesses))
        (is (= "agent" operation))
        (is (contains? bins "agent"))
        (is (= #{:strand/added :strand/updated :batch/applied}
               (:types handler)))
        (doseq [effect [:harness-core-runtime
                        :claude-harness-runtime
                        :codex-harness-runtime
                        :cursor-harness-runtime
                        :pi-harness-runtime
                        :harness-execution-runtime
                        :harness-process-custody]]
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
                                 friendly-id
                                 (millstrand.api.spool.alpha/attr-get
                                  run :identity/id)]
                             (millstrand.api.weaver.alpha/op!
                              rt 'agent
                              ["list" "--by-identity" friendly-id])))
                         allowed (listing :allow-seat)
                         denied (listing :deny-seat)]
                     {:allow (mapv :name allowed)
                      :deny-targets
                      (into #{}
                            (filter #{"terra" "reviewer" "oracle"})
                            (map :name denied))
                      :deny-keeps-pi
                      (contains? (set (map :name denied)) "pi")
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
        (testing "nested agent calls build identity and run provenance"
          (is (= {:origin-to-child true
                  :child-to-grandchild true
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
                           launch #(weaver/op! rt 'agent
                                               ["run" "reviewer" "--interactive"
                                                "--cwd" "/tmp"])
                           origin-run (launch)
                           origin-id (:identity origin-run)
                           child-run (weaver/op!
                                      rt 'agent
                                      ["run" "reviewer" "--interactive"
                                       "--cwd" "/tmp"
                                       "--by-identity" origin-id])
                           child-id (:identity child-run)
                           grandchild-run
                           (weaver/op!
                            rt 'agent
                            ["run" "reviewer" "--interactive"
                             "--cwd" "/tmp"
                             "--by-identity" child-id])
                           grandchild-id (:identity grandchild-run)
                           _ (harnesses/finish!
                              rt (:id grandchild-run)
                              {:status :done :exit-code 0})
                           resumed-run
                           (weaver/op!
                            rt 'agent
                            ["resume" "--run-id" (:id grandchild-run)
                             "--interactive" "--by-identity" child-id])
                           identity-strand #(identity/current rt %)
                           target-ids #(into #{}
                                             (map :to_strand_id)
                                             (graph/outgoing-edges
                                              rt [(:id (identity-strand %))] %2))]
                       {:origin-to-child
                        (= #{(:id (identity-strand child-id))}
                           (target-ids origin-id "parent-of"))
                        :child-to-grandchild
                        (= #{(:id (identity-strand grandchild-id))}
                           (target-ids child-id "parent-of"))
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
                       (require '[ct.spools.harnesses.execution :as execution]
                                '[millstrand.api.current.alpha :as current])
                       (let [context {:runtime (current/runtime)}
                             inspect-var (ns-resolve
                                          'ct.spools.harnesses.execution
                                          'inspect-owned!)]
                         (execution/close-execution! context)
                         [(with-redefs-fn
                            {inspect-var
                             (fn [_runtime]
                               (throw (ex-info "forced open failure" {})))}
                            #(try
                               (execution/open-execution! context)
                               nil
                               (catch clojure.lang.ExceptionInfo error
                                 (ex-message error))))
                          (execution/open-execution! context)])))))
            (testing "core continuation selectors resolve the latest completed run"
              (let [{:keys [first-id second-id by-run by-session by-identity]}
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
                                         {:status :done :exit-code 0})
                              second-run (harnesses/resume! rt (:id first-run) {})
                              second-run (harnesses/finish!
                                          rt (:id second-run)
                                          {:status :done :exit-code 0})
                              session-id (get-in second-run
                                                 [:attributes :harness/session-id])
                              identity (get-in second-run [:attributes :identity/id])]
                          {:first-id (:id first-run)
                           :second-id (:id second-run)
                           :by-run (:id (harnesses/resolve-resume-run
                                         rt {:run-id (:id first-run)}))
                           :by-session (:id (harnesses/resolve-resume-run
                                             rt {:session-id session-id}))
                           :by-identity (:id (harnesses/resolve-resume-run
                                              rt {:identity identity}))})))]
                (is (= first-id by-run))
                (is (= second-id by-session by-identity))))))))))

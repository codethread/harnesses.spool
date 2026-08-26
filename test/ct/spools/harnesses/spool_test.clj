(ns ct.spools.harnesses.spool-test
  "Authoring and activation tests for the consolidated Harnesses spool."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.agent-cli :as agent-cli]
            [ct.spools.harnesses.execution :as execution]
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
    (doseq [declaration-var [#'agent-cli/harness
                             #'execution/on-event
                             #'agent-cli/agent]]
      (is (map? (:millstrand.api.authoring.alpha/declaration
                 (meta declaration-var)))))))

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
            :spools-edn
            {:spools
             {'ct.spools/harnesses
              {:local/root (.getCanonicalPath harnesses-root)}
              'millhouse.spools/identity
              {:local/root (.getCanonicalPath identity-root)}}}
            :init
            "(require '[millstrand.api.current.alpha :as current]
                       '[millstrand.api.runtime.alpha :as runtime])
             (def rt (current/runtime))
             (runtime/module! rt :identity
               {:ns 'millhouse.spools.identity
                :spools ['millhouse.spools/identity]
                :required? true})
             (runtime/module! rt :harnesses
               {:ns 'ct.spools.harnesses.spool
                :spools ['ct.spools/harnesses 'millhouse.spools/identity]
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
                   :operation (:name (weaver/resolve-op rt 'harness))
                   :handler (some #(when (= :on-event (:key %)) %)
                                  (events/handlers rt))
                   :bins (set (map :name (:bins (weaver/op! rt 'bins ["list"]))))
                   :lifecycles (get-in (runtime/status rt)
                                       [:lifecycle/outcomes :harnesses])})))]
        (is (= ["claude" "codex" "cursor" "pi"] harnesses))
        (is (= "harness" operation))
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
        (testing "run flags override effort on new strands"
          (is (= ["adaptive" "maximum"]
                 (test-alpha/repl!
                  ctx
                  '(let [rt (millstrand.api.current.alpha/runtime)]
                     (mapv
                      (fn [flag effort]
                        (let [created (millstrand.api.weaver.alpha/op!
                                       rt 'harness
                                       ["run" "pi" "--interactive"
                                        "--cwd" "/tmp" flag effort])]
                          (millstrand.api.spool.alpha/attr-get
                           (millstrand.api.weaver.alpha/show rt (:id created))
                           :harness/effort)))
                      ["--effort" "--thinking"]
                      ["adaptive" "maximum"]))))))
        (testing "aliases expose documentation, model, and open effort"
          (is (= {:registration
                  {:alias "reviewer"
                   :doc "Review with high effort."
                   :parent "terra"
                   :model "reviewer-model"
                   :effort :max
                   :attributes {}}
                  :listing
                  {:name "reviewer"
                   :kind "alias"
                   :doc "Review with high effort."
                   :alias-of "terra"
                   :model "reviewer-model"
                   :effort :max
                   :attributes {}}
                  :portable
                  {:harness/extra-argv []
                   :harness/model "terra-model"
                   :harness/effort "high"}
                  :generated
                  {:harness/extra-argv []
                   :harness/model "reviewer-model"
                   :harness/effort "max"}}
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
                               :attributes {}})
                           registration
                           (harnesses/register-alias!
                            rt :reviewer
                            {:doc "Review with high effort."
                             :parent :terra
                             :model "reviewer-model"
                             :effort :max
                             :attributes {}})]
                       {:registration registration
                        :listing (some #(when (= "reviewer" (:name %)) %)
                                       (harnesses/harnesses rt))
                        :portable (:generated
                                   (harnesses/resolve-harness rt :terra))
                        :generated (:generated
                                    (harnesses/resolve-harness rt :reviewer))}))))))
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

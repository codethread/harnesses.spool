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

(defn with-managed-world
  "Run a body in an isolated managed-startup Weaver world."
  [f]
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

(def setup
  "Forms installed before each managed-startup world assertion."
  '(do
     (require '[clojure.data.json :as json]
              '[ct.spools.harnesses :as harnesses]
              '[ct.spools.harnesses.execution :as execution]
              '[ct.spools.harnesses.internal.launcher :as launcher]
              '[ct.spools.harnesses.internal.managed-startup :as managed]
              '[millhouse.spools.identity :as identity]
              '[millstrand.api.current.alpha :as current]
              '[millstrand.api.graph.alpha :as graph]
              '[millstrand.api.hooks.alpha :as hooks]
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
             (graph/outgoing-edges rt [(:id strand)] type)))
     (def reject-attachment-batches? (atom false))
     (defn reject-attachment-batch [ctx]
       (when (and @reject-attachment-batches?
                  (some #(contains? (:attributes %)
                                    :harness/native-attachment-source)
                        (get-in ctx [:batch/payload :strands])))
         (throw (ex-info "reject managed attachment batch"
                         {:code "test/reject-attachment"}))))
     (hooks/register-hook!
      rt :reject-managed-attachment #{:batch/apply-before-commit}
      (symbol (str (ns-name *ns*)) "reject-attachment-batch") {})))

(defn eval-world
  "Evaluate an assertion body after managed-startup world setup."
  [ctx body]
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

(deftest invalid-fences-pins-and-atomic-rejection-do-not-attach
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
                    _ (harnesses/stop! rt (:id writer) {:reason "release"})
                    atomic-before
                    [(weaver/show rt (:id codex))
                     (identity/current rt identity-id)
                     (targets (identity/current rt identity-id) "performed")]
                    _ (reset! reject-attachment-batches? true)
                    atomic-failure (try-start "actual" "root" bootstrap)
                    _ (reset! reject-attachment-batches? false)
                    atomic-after
                    [(weaver/show rt (:id codex))
                     (identity/current rt identity-id)
                     (targets (identity/current rt identity-id) "performed")]
                    attached (try-start "actual" "root" bootstrap)
                    different (try-start "other-native" "root" bootstrap)
                    after (identity/current rt identity-id)
                    never-run (harnesses/create!
                               rt {:harness :codex :mode :interactive
                                   :cwd "/tmp/never" :title "never launched"})
                    _ (harnesses/stop! rt (:id never-run)
                                       {:reason "stopped before launch"})
                    never-bootstrap
                    (assoc bootstrap
                           "run-id" (:id never-run)
                           "identity" (attr never-run :identity/id)
                           "reservation-id"
                           (attr never-run :identity/reservation-id)
                           "cwd" "/tmp/never"
                           "attempt" nil
                           "invocation" nil)
                    never-before
                    [(weaver/show rt (:id never-run))
                     (identity/current rt (attr never-run :identity/id))]
                    never-failure
                    (failure #(harnesses/managed-startup!
                               rt {:harness "codex"
                                   :native-session-id "never-started-thread"
                                   :cwd "/tmp/never"
                                   :scope "root"
                                   :bootstrap never-bootstrap}))
                    never-after
                    [(weaver/show rt (:id never-run))
                     (identity/current rt (attr never-run :identity/id))]
                    pi-run (harnesses/create!
                            rt {:harness :pi :mode :interactive
                                :cwd "/tmp/pi" :session-id "pi-expected"
                                :title "pi"})
                    _ (harnesses/begin-attempt! rt (:id pi-run))
                    pi-bootstrap (harnesses/managed-bootstrap rt (:id pi-run))
                    try-pi (fn [native document]
                             (failure #(harnesses/managed-startup!
                                        rt {:harness "pi"
                                            :native-session-id native
                                            :cwd "/tmp/pi"
                                            :scope "root"
                                            :bootstrap document})))
                    pi-before
                    [(weaver/show rt (:id pi-run))
                     (identity/current rt (attr pi-run :identity/id))]
                    pi-omitted
                    (try-pi "pi-expected"
                            (dissoc pi-bootstrap
                                    "expected-native-session-id"))
                    pi-changed
                    (try-pi "pi-changed"
                            (assoc pi-bootstrap
                                   "expected-native-session-id"
                                   "pi-changed"))
                    pi-mismatch (try-pi "pi-wrong" pi-bootstrap)
                    pi-after
                    [(weaver/show rt (:id pi-run))
                     (identity/current rt (attr pi-run :identity/id))]]
                {:attempt (:attempt started)
                 :stale stale
                 :child child
                 :conflict conflict
                 :atomic-failure atomic-failure
                 :atomic-unchanged (= atomic-before atomic-after)
                 :attached attached
                 :different different
                 :after-state (attr after :identity/reservation-state)
                 :after-native (attr after :identity/native-session-id)
                 :never-failure never-failure
                 :never-unchanged (= never-before never-after)
                 :pi-omitted pi-omitted
                 :pi-changed pi-changed
                 :pi-mismatch pi-mismatch
                 :pi-unchanged (= pi-before pi-after)}))]
        (is (re-find #"invocation" (get-in result [:stale :message])))
        (is (re-find #"scope" (get-in result [:child :message])))
        (is (re-find #"active managed writer"
                     (get-in result [:conflict :message])))
        (is (some? (:atomic-failure result)))
        (is (true? (:atomic-unchanged result)))
        (is (nil? (:attached result)))
        (is (re-find #"already attached"
                     (get-in result [:different :message])))
        (is (= "attached" (:after-state result)))
        (is (= "actual" (:after-native result)))
        (is (re-find #"durable launch fence"
                     (get-in result [:never-failure :message])))
        (is (true? (:never-unchanged result)))
        (is (re-find #"requires its native session pin"
                     (get-in result [:pi-omitted :message])))
        (is (re-find #"durable pin"
                     (get-in result [:pi-changed :message])))
        (is (re-find #"durable pin"
                     (get-in result [:pi-mismatch :message])))
        (is (true? (:pi-unchanged result)))))))

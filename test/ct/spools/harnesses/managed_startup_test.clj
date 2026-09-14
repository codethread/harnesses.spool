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

(deftest pre-reservation-runs-finish-under-the-new-backend
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [legacy-create
                    (fn [request]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create! rt request)))
                    snapshot
                    (fn [run]
                      (let [identity-strand
                            (identity/current rt (attr run :identity/id))]
                        {:run (weaver/show rt (:id run))
                         :identity identity-strand
                         :performed (targets identity-strand "performed")
                         :strand-count (count (weaver/list rt))}))
                    completed-run
                    (legacy-create
                     {:harness :codex :mode :interactive
                      :cwd "/tmp/legacy-complete"
                      :title "serialized legacy completion"})
                    completed-provisional
                    (attr completed-run :harness/session-id)
                    completed-start
                    (harnesses/begin-attempt! rt (:id completed-run))
                    identity-before
                    (identity/current rt (attr completed-run :identity/id))
                    stale-before (snapshot completed-run)
                    stale
                    (harnesses/finish!
                     rt (:id completed-run)
                     {:status :done :exit-code 0 :result "stale"
                      :session-id "legacy-thread"
                      :session-usable true
                      :invocation "originating-stale-invocation"})
                    stale-after (snapshot completed-run)
                    completed
                    (harnesses/finish!
                     rt (:id completed-run)
                     {:status :done :exit-code 0 :result "complete"
                      :session-id "legacy-thread"
                      :session-usable true
                      :invocation (:invocation completed-start)})
                    terminal-before (snapshot completed)
                    terminal-replay
                    (harnesses/finish!
                     rt (:id completed)
                     {:status :done :exit-code 0
                      :session-usable true
                      :invocation "terminal-replay"})
                    terminal-after (snapshot completed)
                    before-resume-count (count (weaver/list rt))
                    legacy-resume
                    (failure #(harnesses/resume! rt (:id completed) {}))
                    after-resume-count (count (weaver/list rt))
                    fresh
                    (harnesses/create!
                     rt {:harness :codex :mode :interactive
                         :cwd "/tmp/legacy-complete"
                         :title "fresh after legacy"
                         :after (:id completed)})
                    cancelled-run
                    (legacy-create
                     {:harness :pi :mode :interactive
                      :cwd "/tmp/legacy-cancel"
                      :session-id "legacy-pi-session"
                      :title "serialized legacy cancellation"})
                    cancelled-start
                    (harnesses/begin-attempt! rt (:id cancelled-run))
                    _ (harnesses/stop! rt (:id cancelled-run)
                                       {:reason "replacement cancellation"})
                    cancelled
                    (harnesses/finish!
                     rt (:id cancelled-run)
                     {:status :failed :exit-code 143
                      :error "cancelled"
                      :session-id "legacy-pi-session"
                      :session-usable true
                      :invocation (:invocation cancelled-start)
                      :evidence {:settled true
                                 :settlement "graceful-cancellation"
                                 :cancelled? true}})
                    late-run
                    (legacy-create
                     {:harness :codex :mode :interactive
                      :cwd "/tmp/legacy-late"
                      :title "serialized legacy late custody"})
                    late-start
                    (harnesses/begin-attempt! rt (:id late-run))
                    _ (harnesses/stop! rt (:id late-run)
                                       {:reason "late custody"})
                    late-failed
                    (harnesses/finish!
                     rt (:id late-run)
                     {:status :failed :exit-code 1
                      :error "primary failure"
                      :invocation (:invocation late-start)
                      :evidence {:settled false
                                 :settlement "no-terminal-evidence"}})
                    late-settled
                    (harnesses/settle-outcome!
                     rt (:id late-failed)
                     {:status :failed :exit-code 143
                      :error "cancelled"
                      :session-id "legacy-late-thread"
                      :session-usable true
                      :invocation (:invocation late-start)}
                     {:settled true
                      :settlement "graceful-cancellation"
                      :cancelled? true})
                    malformed-run
                    (harnesses/create!
                     rt {:harness :codex :mode :interactive
                         :cwd "/tmp/malformed-current"
                         :title "malformed current reservation"})
                    malformed-start
                    (harnesses/begin-attempt! rt (:id malformed-run))
                    malformed-identity
                    (attr malformed-run :identity/id)
                    _ (weaver/update!
                       rt (:id malformed-run)
                       {:attributes {:identity/reservation-id nil}})
                    malformed-before
                    [(weaver/show rt (:id malformed-run))
                     (identity/current rt malformed-identity)
                     (targets (identity/current rt malformed-identity)
                              "performed")]
                    malformed-failure
                    (failure
                     #(harnesses/finish!
                       rt (:id malformed-run)
                       {:status :done :exit-code 0 :result "must reject"
                        :session-id "malformed-thread"
                        :session-usable true
                        :invocation (:invocation malformed-start)}))
                    malformed-after
                    [(weaver/show rt (:id malformed-run))
                     (identity/current rt malformed-identity)
                     (targets (identity/current rt malformed-identity)
                              "performed")]]
                {:completed-status (attr completed :harness/status)
                 :completed-substatus (attr completed :harness/substatus)
                 :completed-session (attr completed :harness/session-id)
                 :completed-usable (attr completed :harness/session-usable)
                 :completed-reservation
                 (attr completed :identity/reservation-id)
                 :completed-native-attached
                 (attr completed :harness/native-attached)
                 :completed-provisional-attribute
                 (attr completed :harness/provisional-session-id)
                 :identity-native-before
                 (attr identity-before :identity/native-session-id)
                 :identity-native-after
                 (attr (identity/current rt (attr completed :identity/id))
                       :identity/native-session-id)
                 :stale-returned-unchanged
                 (= (:run stale-before) stale)
                 :stale-no-write (= stale-before stale-after)
                 :terminal-returned-unchanged
                 (= (:run terminal-before) terminal-replay)
                 :terminal-no-write (= terminal-before terminal-after)
                 :completed-provisional completed-provisional
                 :legacy-resume legacy-resume
                 :resume-no-write (= before-resume-count after-resume-count)
                 :fresh-identity-different
                 (not= (attr completed :identity/id)
                       (attr fresh :identity/id))
                 :fresh-reservation (attr fresh :identity/reservation-id)
                 :cancelled-status (attr cancelled :harness/status)
                 :cancelled-substatus (attr cancelled :harness/substatus)
                 :cancelled-settled (attr cancelled :harness/settled)
                 :cancelled-settlement
                 (attr cancelled :harness/settlement)
                 :cancelled-reservation
                 (attr cancelled :identity/reservation-id)
                 :late-status (attr late-settled :harness/status)
                 :late-error (attr late-settled :harness/error)
                 :late-settled (attr late-settled :harness/settled)
                 :late-settlement
                 (attr late-settled :harness/settlement)
                 :late-session (attr late-settled :harness/session-id)
                 :late-usable (attr late-settled :harness/session-usable)
                 :malformed-failure malformed-failure
                 :malformed-unchanged
                 (= malformed-before malformed-after)}))]
        (testing "an old serialized binding completes without invented evidence"
          (is (= "stopped" (:completed-status result)))
          (is (= "completed" (:completed-substatus result)))
          (is (= "legacy-thread" (:completed-session result)))
          (is (= "true" (:completed-usable result)))
          (is (nil? (:completed-reservation result)))
          (is (nil? (:completed-native-attached result)))
          (is (nil? (:completed-provisional-attribute result)))
          (is (= (:completed-provisional result)
                 (:identity-native-before result)
                 (:identity-native-after result))))
        (testing "stale and terminal callbacks are full no-ops"
          (is (true? (:stale-returned-unchanged result)))
          (is (true? (:stale-no-write result)))
          (is (true? (:terminal-returned-unchanged result)))
          (is (true? (:terminal-no-write result))))
        (testing "legacy continuity is explicit while fresh work remains native-v1"
          (is (re-find #"legacy Codex run has no verified native binding"
                       (get-in result [:legacy-resume :data :reason])))
          (is (true? (:resume-no-write result)))
          (is (true? (:fresh-identity-different result)))
          (is (string? (:fresh-reservation result))))
        (testing "a replacement backend records cancellation and late custody"
          (is (= "stopped" (:cancelled-status result)))
          (is (= "requested" (:cancelled-substatus result)))
          (is (= "true" (:cancelled-settled result)))
          (is (= "graceful-cancellation"
                 (:cancelled-settlement result)))
          (is (nil? (:cancelled-reservation result)))
          (is (= "failed" (:late-status result)))
          (is (= "primary failure" (:late-error result)))
          (is (= "true" (:late-settled result)))
          (is (= "graceful-cancellation" (:late-settlement result)))
          (is (= "legacy-late-thread" (:late-session result)))
          (is (= "true" (:late-usable result))))
        (testing "a damaged reservation-backed run is not treated as legacy"
          (is (re-find #"no identity reservation"
                       (get-in result [:malformed-failure :message])))
          (is (true? (:malformed-unchanged result))))))))

(deftest exact-binding-legacy-pi-continuation-launches-and-retries
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [root
                    (with-redefs [managed/managed-harness? (constantly false)]
                      (harnesses/create!
                       rt {:harness :pi :mode :interactive
                           :cwd "/tmp/legacy-pi-resume"
                           :session-id "legacy-pi-native"
                           :title "legacy Pi root"}))
                    root-start (harnesses/begin-attempt! rt (:id root))
                    root (harnesses/finish!
                          rt (:id root)
                          {:status :done :exit-code 0
                           :session-id "legacy-pi-native"
                           :session-usable true
                           :invocation (:invocation root-start)})
                    eligibility (harnesses/resume-eligibility rt (:id root))
                    resumed (harnesses/resume!
                             rt (:id root) {:prompt "Continue safely."})
                    identity-id (attr resumed :identity/id)
                    launcher-path (launcher/write! rt resumed ["pi"] {})
                    launcher-before (slurp launcher-path)
                    resumed-start
                    (execution/mark-interactive-running! rt (:id resumed))
                    launcher-after (slurp launcher-path)
                    bootstrap (harnesses/managed-bootstrap rt (:id resumed))
                    process-spec (#'execution/process-spec
                                  rt resumed-start
                                  {:argv ["pi"] :env {} :stdin nil})
                    failed (harnesses/finish!
                            rt (:id resumed)
                            {:status :failed :exit-code 1
                             :error "retry legacy continuation"
                             :session-id "legacy-pi-native"
                             :session-usable true
                             :invocation
                             (attr resumed-start :harness/invocation)
                             :evidence {:settled true
                                        :settlement "process-exit"}})
                    retried (harnesses/retry! rt (:id failed) {})
                    retry-launcher
                    (launcher/write! rt retried ["pi" "--session"
                                                 "legacy-pi-native"] {})
                    retry-launcher-before (slurp retry-launcher)
                    retry-started
                    (execution/mark-interactive-running! rt (:id retried))
                    retry-launcher-after (slurp retry-launcher)
                    identity-strand (identity/current rt identity-id)]
                {:eligibility eligibility
                 :root-id (:id root)
                 :run-id (:id resumed)
                 :same-identity
                 (= (attr root :identity/id) identity-id
                    (attr retried :identity/id))
                 :same-session
                 (= "legacy-pi-native"
                    (attr root :harness/session-id)
                    (attr resumed :harness/session-id)
                    (attr retried :harness/session-id))
                 :reservation (attr retried :identity/reservation-id)
                 :provisional
                 (attr retried :harness/provisional-session-id)
                 :native-attached
                 (attr retried :harness/native-attached)
                 :performed (targets identity-strand "performed")
                 :launcher-before launcher-before
                 :launcher-after launcher-after
                 :bootstrap bootstrap
                 :process-env (:env process-spec)
                 :retry-launcher-before retry-launcher-before
                 :retry-launcher-after retry-launcher-after
                 :retry-bootstrap
                 (harnesses/managed-bootstrap rt (:id retry-started))}))]
        (testing "exact pre-upgrade Pi binding remains natively resumable"
          (is (= {:eligible? true
                  :reason "settled with a verified usable session"}
                 (:eligibility result)))
          (is (true? (:same-identity result)))
          (is (true? (:same-session result)))
          (is (= #{(:root-id result) (:run-id result)}
                 (:performed result))))
        (testing "legacy continuation does not mint startup-v1 evidence"
          (is (nil? (:reservation result)))
          (is (nil? (:provisional result)))
          (is (nil? (:native-attached result)))
          (is (nil? (:bootstrap result)))
          (is (nil? (:retry-bootstrap result)))
          (is (not (contains? (:process-env result)
                              "MILLSTRAND_MANAGED_BOOTSTRAP"))))
        (testing "interactive launch and retry retain the legacy transport"
          (doseq [source [(:launcher-before result)
                          (:launcher-after result)
                          (:retry-launcher-before result)
                          (:retry-launcher-after result)]]
            (is (not (str/includes?
                      source "MILLSTRAND_MANAGED_BOOTSTRAP")))))))))

(deftest stale-and-terminal-managed-finishes-are-complete-no-ops
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [snapshot
                    (fn [run]
                      (let [current (weaver/show rt (:id run))
                            identity-strand
                            (identity/current rt (attr current :identity/id))]
                        {:run current
                         :identity identity-strand
                         :performed (targets identity-strand "performed")
                         :strand-count (count (weaver/list rt))}))
                    probe
                    (fn [run outcome]
                      (let [before (snapshot run)
                            returned (harnesses/finish! rt (:id run) outcome)
                            after (snapshot run)]
                        {:returned-unchanged (= (:run before) returned)
                         :no-write (= before after)}))]
                (into {}
                      (for [provider [:codex :pi]]
                        (let [run (harnesses/create!
                                   rt {:harness provider :mode :interactive
                                       :cwd "/tmp/managed-finish-no-op"
                                       :title (str (name provider) " no-op")})
                              started (harnesses/begin-attempt! rt (:id run))
                              stale
                              (into {}
                                    (for [usable? [true false]]
                                      [usable?
                                       (probe
                                        run
                                        {:status :done :exit-code 0
                                         :session-id "unrelated-stale-session"
                                         :session-usable usable?
                                         :invocation "stale-invocation"})]))
                              terminal
                              (harnesses/finish!
                               rt (:id run)
                               {:status :failed :exit-code 1
                                :error "terminal baseline"
                                :invocation (:invocation started)
                                :evidence {:settled true
                                           :settlement "process-exit"}})
                              terminal
                              (into {}
                                    (for [usable? [true false]]
                                      [usable?
                                       (probe
                                        terminal
                                        {:status :done :exit-code 0
                                         :session-id "unrelated-terminal-session"
                                         :session-usable usable?
                                         :invocation "terminal-replay"})]))]
                          [provider {:stale stale :terminal terminal}])))))]
        (doseq [provider [:codex :pi]
                stage [:stale :terminal]
                usable? [true false]]
          (testing (str (name provider) " " (name stage)
                        " callback with usable=" usable?)
            (is (true? (get-in result
                               [provider stage usable? :returned-unchanged])))
            (is (true? (get-in result
                               [provider stage usable? :no-write])))))))))

(deftest raw-legacy-pi-continuation-rejects-request-mismatches-before-writes
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [root
                    (with-redefs [managed/managed-harness? (constantly false)]
                      (harnesses/create!
                       rt {:harness :pi :mode :interactive
                           :cwd "/tmp/legacy-raw-resume"
                           :session-id "legacy-raw-native"
                           :title "legacy raw Pi root"}))
                    started (harnesses/begin-attempt! rt (:id root))
                    root (harnesses/finish!
                          rt (:id root)
                          {:status :done :exit-code 0
                           :session-id "legacy-raw-native"
                           :session-usable true
                           :invocation (:invocation started)})
                    identity-strand
                    (identity/current rt (attr root :identity/id))
                    snapshot
                    (fn []
                      {:strand-ids (->> (weaver/list rt)
                                        (map :id)
                                        sort
                                        vec)
                       :predecessor (weaver/show rt (:id root))
                       :identity (identity/current rt (attr root :identity/id))
                       :performed (->> (graph/outgoing-edges
                                        rt [(:id identity-strand)] "performed")
                                       (sort-by pr-str)
                                       vec)
                       :resumes (->> (graph/incoming-edges
                                      rt [(:id root)] "resumes")
                                     (sort-by pr-str)
                                     vec)})
                    before (snapshot)
                    provider-failure
                    (failure #(harnesses/create!
                               rt {:harness :codex :mode :interactive
                                   :cwd "/tmp/legacy-raw-resume"
                                   :session-id "legacy-raw-native"
                                   :resumes (:id root)
                                   :title "wrong legacy provider"}))
                    after-provider (snapshot)
                    session-failure
                    (failure #(harnesses/create!
                               rt {:harness :pi :mode :interactive
                                   :cwd "/tmp/legacy-raw-resume"
                                   :session-id "wrong-native-session"
                                   :resumes (:id root)
                                   :title "wrong legacy session"}))
                    after-session (snapshot)
                    missing-session-failure
                    (failure #(harnesses/create!
                               rt {:harness :pi :mode :interactive
                                   :cwd "/tmp/legacy-raw-resume"
                                   :resumes (:id root)
                                   :title "missing legacy session"}))
                    after-missing (snapshot)]
                {:provider-failure provider-failure
                 :session-failure session-failure
                 :missing-session-failure missing-session-failure
                 :provider-no-write (= before after-provider)
                 :session-no-write (= before after-session)
                 :missing-no-write (= before after-missing)}))]
        (testing "resolved provider and requested session remain exact"
          (is (re-find #"cannot change its provider"
                       (get-in result [:provider-failure :message])))
          (is (re-find #"exact explicit native session"
                       (get-in result [:session-failure :message])))
          (is (re-find #"exact explicit native session"
                       (get-in result [:missing-session-failure :message]))))
        (testing "mismatches create no child, provenance, or continuation mark"
          (is (true? (:provider-no-write result)))
          (is (true? (:session-no-write result)))
          (is (true? (:missing-no-write result))))))))

(deftest current-managed-continuation-mismatches-fail-before-commit-writes
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [snapshot
                    (fn [run]
                      (let [current (weaver/show rt (:id run))
                            identity-strand
                            (identity/current rt (attr current :identity/id))]
                        {:strand-ids (->> (weaver/list rt)
                                          (map :id)
                                          sort
                                          vec)
                         :predecessor current
                         :identity identity-strand
                         :performed (->> (graph/outgoing-edges
                                          rt [(:id identity-strand)]
                                          "performed")
                                         (sort-by pr-str)
                                         vec)
                         :resumes (->> (graph/incoming-edges
                                        rt [(:id current)] "resumes")
                                       (sort-by pr-str)
                                       vec)}))]
                (into {}
                      (for [provider [:codex :pi]]
                        (let [session-id (str "current-" (name provider))
                              run (harnesses/create!
                                   rt {:harness provider :mode :interactive
                                       :cwd "/tmp/current-managed-resume"
                                       :session-id session-id
                                       :title (str (name provider)
                                                   " current predecessor")})
                              started (harnesses/begin-attempt! rt (:id run))
                              bootstrap
                              (harnesses/managed-bootstrap rt (:id run))
                              _ (harnesses/managed-startup!
                                 rt {:harness (name provider)
                                     :native-session-id session-id
                                     :cwd "/tmp/current-managed-resume"
                                     :scope "root"
                                     :bootstrap bootstrap})
                              predecessor
                              (harnesses/finish!
                               rt (:id run)
                               {:status :done :exit-code 0
                                :session-id session-id
                                :session-usable true
                                :invocation (:invocation started)})
                              before (snapshot predecessor)
                              other-provider
                              (if (= :pi provider) :codex :pi)
                              provider-failure
                              (failure #(harnesses/create!
                                         rt {:harness other-provider
                                             :mode :interactive
                                             :cwd "/tmp/current-managed-resume"
                                             :session-id session-id
                                             :resumes (:id predecessor)
                                             :title "wrong current provider"}))
                              after-provider (snapshot predecessor)
                              session-failure
                              (failure #(harnesses/create!
                                         rt {:harness provider
                                             :mode :interactive
                                             :cwd "/tmp/current-managed-resume"
                                             :session-id "wrong-current-session"
                                             :resumes (:id predecessor)
                                             :title "wrong current session"}))
                              after-session (snapshot predecessor)
                              missing-failure
                              (failure #(harnesses/create!
                                         rt {:harness provider
                                             :mode :interactive
                                             :cwd "/tmp/current-managed-resume"
                                             :resumes (:id predecessor)
                                             :title "missing current session"}))
                              after-missing (snapshot predecessor)]
                          [provider
                           {:provider-failure provider-failure
                            :session-failure session-failure
                            :missing-failure missing-failure
                            :provider-no-write (= before after-provider)
                            :session-no-write (= before after-session)
                            :missing-no-write (= before after-missing)}])))))]
        (doseq [provider [:codex :pi]]
          (testing (str (name provider)
                        " continuation intent is validated before commit")
            (is (re-find #"cannot change its provider"
                         (get-in result
                                 [provider :provider-failure :message])))
            (is (re-find #"exact explicit native session"
                         (get-in result
                                 [provider :session-failure :message])))
            (is (re-find #"exact explicit native session"
                         (get-in result
                                 [provider :missing-failure :message])))
            (is (true? (get-in result [provider :provider-no-write])))
            (is (true? (get-in result [provider :session-no-write])))
            (is (true? (get-in result [provider :missing-no-write])))))))))

(deftest positive-legacy-evidence-requires-an-exactly-validated-session
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [legacy-create
                    (fn [provider suffix]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create!
                         rt {:harness provider :mode :interactive
                             :cwd "/tmp/legacy-required-session"
                             :session-id (str "legacy-" suffix)
                             :title (str "legacy " suffix)})))
                    positive-outcome
                    (fn [kind invocation]
                      (merge
                       (if (= :done kind)
                         {:status :done :exit-code 0
                          :session-usable false}
                         {:status :failed :exit-code 1
                          :error "usable failure"
                          :session-usable true})
                       {:invocation invocation}))
                    snapshot
                    (fn [run]
                      (let [identity-strand
                            (identity/current rt (attr run :identity/id))]
                        {:run (weaver/show rt (:id run))
                         :identity identity-strand
                         :performed (->> (graph/outgoing-edges
                                          rt [(:id identity-strand)]
                                          "performed")
                                         (sort-by pr-str)
                                         vec)
                         :strand-count (count (weaver/list rt))}))
                    finish-results
                    (into {}
                          (for [provider [:codex :pi]
                                kind [:done :usable]]
                            (let [suffix (str (name provider) "-finish-"
                                              (name kind))
                                  run (legacy-create provider suffix)
                                  started (harnesses/begin-attempt!
                                           rt (:id run))
                                  before (snapshot run)
                                  rejected
                                  (failure #(harnesses/finish!
                                             rt (:id run)
                                             (positive-outcome
                                              kind (:invocation started))))]
                              [[provider kind]
                               {:failure rejected
                                :unchanged (= before (snapshot run))}])))
                    settle-results
                    (into {}
                          (for [provider [:codex :pi]
                                kind [:done :usable]]
                            (let [suffix (str (name provider) "-settle-"
                                              (name kind))
                                  run (legacy-create provider suffix)
                                  started (harnesses/begin-attempt!
                                           rt (:id run))
                                  terminal
                                  (harnesses/finish!
                                   rt (:id run)
                                   {:status :failed :exit-code 1
                                    :error "awaiting custody"
                                    :invocation (:invocation started)
                                    :evidence
                                    {:settled false
                                     :settlement "no-terminal-evidence"}})
                                  before (snapshot terminal)
                                  rejected
                                  (failure #(harnesses/settle-outcome!
                                             rt (:id terminal)
                                             (positive-outcome
                                              kind (:invocation started))
                                             {:settled true
                                              :settlement "process-exit"}))]
                              [[provider kind]
                               {:failure rejected
                                :unchanged (= before (snapshot terminal))}])))
                    wrong-finish
                    (let [run (legacy-create :pi "pi-wrong-finish")
                          started (harnesses/begin-attempt! rt (:id run))
                          before (snapshot run)
                          rejected
                          (failure #(harnesses/finish!
                                     rt (:id run)
                                     (assoc (positive-outcome
                                             :done (:invocation started))
                                            :session-id "unrelated-pi")))]
                      {:failure rejected
                       :unchanged (= before (snapshot run))})
                    wrong-settle
                    (let [run (legacy-create :pi "pi-wrong-settle")
                          started (harnesses/begin-attempt! rt (:id run))
                          terminal
                          (harnesses/finish!
                           rt (:id run)
                           {:status :failed :exit-code 1
                            :error "awaiting custody"
                            :invocation (:invocation started)
                            :evidence {:settled false
                                       :settlement "no-terminal-evidence"}})
                          before (snapshot terminal)
                          rejected
                          (failure #(harnesses/settle-outcome!
                                     rt (:id terminal)
                                     (assoc (positive-outcome
                                             :done (:invocation started))
                                            :session-id "unrelated-pi")
                                     {:settled true
                                      :settlement "process-exit"}))]
                      {:failure rejected
                       :unchanged (= before (snapshot terminal))})]
                {:finish finish-results
                 :settle settle-results
                 :wrong-finish wrong-finish
                 :wrong-settle wrong-settle}))]
        (doseq [path [:finish :settle]
                provider [:codex :pi]
                kind [:done :usable]]
          (testing (str (name provider) " " (name path) " " (name kind)
                        " evidence requires a supplied session")
            (is (re-find #"requires a native session id"
                         (get-in result
                                 [path [provider kind] :failure :message])))
            (is (true? (get-in result
                               [path [provider kind] :unchanged])))))
        (doseq [path [:wrong-finish :wrong-settle]]
          (testing (str (name path) " keeps Pi exact even when unusable")
            (is (re-find #"does not match its durable binding"
                         (get-in result [path :failure :message])))
            (is (true? (get-in result [path :unchanged])))))))))

(deftest legacy-positive-evidence-and-continuation-fail-before-writes
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [legacy-create
                    (fn [session-id title]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create!
                         rt {:harness :pi :mode :interactive
                             :cwd "/tmp/legacy-pi-fences"
                             :session-id session-id
                             :title title})))
                    snapshot
                    (fn [run]
                      (let [identity-id (attr run :identity/id)]
                        [(weaver/show rt (:id run))
                         (identity/current rt identity-id)
                         (targets (identity/current rt identity-id)
                                  "performed")
                         (count (weaver/list rt))]))
                    ready (legacy-create "legacy-ready" "legacy ready")
                    ready-before (snapshot ready)
                    ready-failure
                    (failure #(harnesses/finish!
                               rt (:id ready)
                               {:status :done :exit-code 0
                                :session-id "legacy-ready"
                                :session-usable true
                                :invocation "forged-ready"}))
                    ready-unchanged (= ready-before (snapshot ready))
                    missing (legacy-create "legacy-missing" "legacy missing")
                    missing-start (harnesses/begin-attempt! rt (:id missing))
                    _ (weaver/update!
                       rt (:id missing)
                       {:attributes {:harness/invocation nil}})
                    missing-before (snapshot missing)
                    missing-failure
                    (failure #(harnesses/finish!
                               rt (:id missing)
                               {:status :done :exit-code 0
                                :session-id "legacy-missing"
                                :session-usable true
                                :invocation (:invocation missing-start)}))
                    missing-unchanged (= missing-before (snapshot missing))
                    token (legacy-create "legacy-token" "legacy token")
                    token-start (harnesses/begin-attempt! rt (:id token))
                    token-before (snapshot token)
                    token-failure
                    (failure #(harnesses/finish!
                               rt (:id token)
                               {:status :done :exit-code 0
                                :session-id "legacy-token"
                                :session-usable true}))
                    token-unchanged (= token-before (snapshot token))
                    late (legacy-create "legacy-late-fence"
                                        "legacy late fence")
                    late (harnesses/finish!
                          rt (:id late)
                          {:status :failed :error "prelaunch"})
                    late-before (snapshot late)
                    late-failure
                    (failure #(harnesses/settle-outcome!
                               rt (:id late)
                               {:status :done :exit-code 0
                                :session-id "legacy-late-fence"
                                :session-usable true
                                :invocation "forged-late"}
                               {:settled true :settlement "process-exit"}))
                    late-unchanged (= late-before (snapshot late))
                    late-session
                    (legacy-create "legacy-late-session"
                                   "legacy late session")
                    late-session-start
                    (harnesses/begin-attempt! rt (:id late-session))
                    late-session
                    (harnesses/finish!
                     rt (:id late-session)
                     {:status :failed :exit-code 1
                      :error "awaiting custody"
                      :invocation (:invocation late-session-start)
                      :evidence {:settled false
                                 :settlement "no-terminal-evidence"}})
                    late-session-before (snapshot late-session)
                    late-session-failure
                    (failure #(harnesses/settle-outcome!
                               rt (:id late-session)
                               {:status :done :exit-code 0
                                :session-id "wrong-late-session"
                                :session-usable true
                                :invocation (:invocation late-session-start)}
                               {:settled true :settlement "process-exit"}))
                    late-session-unchanged
                    (= late-session-before (snapshot late-session))
                    valid (legacy-create "legacy-valid" "legacy valid")
                    valid-start (harnesses/begin-attempt! rt (:id valid))
                    valid (harnesses/finish!
                           rt (:id valid)
                           {:status :done :exit-code 0
                            :session-id "legacy-valid"
                            :session-usable true
                            :invocation (:invocation valid-start)})
                    _ (weaver/update!
                       rt (:id valid)
                       {:attributes {:harness/session-id "mismatched-run"}})
                    mismatch-before (snapshot valid)
                    mismatch-failure
                    (failure #(harnesses/resume! rt (:id valid) {}))
                    mismatch-unchanged (= mismatch-before (snapshot valid))
                    unproven
                    (weaver/add!
                     rt {:title "unproven legacy Pi"
                         :attributes
                         (-> (:attributes valid)
                             (assoc :harness/session-id "legacy-valid")
                             (dissoc :harness/continued))})
                    unproven-before
                    [(weaver/show rt (:id unproven))
                     (identity/current rt (attr unproven :identity/id))
                     (targets (identity/current rt
                                                (attr unproven :identity/id))
                              "performed")
                     (count (weaver/list rt))]
                    unproven-failure
                    (failure #(harnesses/resume! rt (:id unproven) {}))
                    unproven-after
                    [(weaver/show rt (:id unproven))
                     (identity/current rt (attr unproven :identity/id))
                     (targets (identity/current rt
                                                (attr unproven :identity/id))
                              "performed")
                     (count (weaver/list rt))]
                    prelaunch
                    (legacy-create "legacy-prelaunch" "legacy prelaunch")
                    prelaunch (harnesses/finish!
                               rt (:id prelaunch)
                               {:status :failed
                                :error "launcher preparation failed"})]
                {:ready [ready-failure ready-unchanged]
                 :missing [missing-failure missing-unchanged]
                 :token [token-failure token-unchanged]
                 :late [late-failure late-unchanged]
                 :late-session [late-session-failure
                                late-session-unchanged]
                 :mismatch [mismatch-failure mismatch-unchanged]
                 :unproven [unproven-failure
                            (= unproven-before unproven-after)]
                 :prelaunch-status (attr prelaunch :harness/status)
                 :prelaunch-error (attr prelaunch :harness/error)}))]
        (testing "positive legacy evidence requires a durable exact fence"
          (doseq [[failure unchanged?]
                  (map result [:ready :missing :token :late])]
            (is (re-find #"(durable launch fence|invocation)"
                         (:message failure)))
            (is (true? unchanged?))))
        (testing "malformed Pi session, binding, and provenance write nothing"
          (is (re-find #"does not match"
                       (get-in result [:late-session 0 :message])))
          (is (true? (get-in result [:late-session 1])))
          (is (re-find #"does not match" (get-in result [:mismatch 0 :message])))
          (is (true? (get-in result [:mismatch 1])))
          (is (re-find #"did not perform"
                       (get-in result [:unproven 0 :message])))
          (is (true? (get-in result [:unproven 1]))))
        (testing "a genuine prelaunch failure remains recordable"
          (is (= "failed" (:prelaunch-status result)))
          (is (= "launcher preparation failed"
                 (:prelaunch-error result))))))))

(deftest legacy-negative-custody-survives-invalid-identity-binding
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [legacy-create
                    (fn [harness session-id title]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create!
                         rt {:harness harness :mode :interactive
                             :cwd "/tmp/legacy-negative-custody"
                             :session-id session-id
                             :title title})))
                    fail-unsettled
                    (fn [run]
                      (let [started (harnesses/begin-attempt! rt (:id run))]
                        {:invocation (:invocation started)
                         :run
                         (harnesses/finish!
                          rt (:id run)
                          {:status :failed :exit-code 1
                           :error "primary provider failure"
                           :invocation (:invocation started)
                           :evidence {:settled false
                                      :settlement
                                      "no-terminal-evidence"}})}))
                    cancellation
                    (fn [session-id invocation]
                      {:status :failed :exit-code 143
                       :error "cancelled"
                       :session-id session-id
                       :session-usable false
                       :invocation invocation})
                    evidence {:settled true
                              :settlement "graceful-cancellation"
                              :cancelled? true}
                    codex-start
                    (fail-unsettled
                     (legacy-create :codex "legacy-codex-custody"
                                    "legacy Codex custody"))
                    codex-run (:run codex-start)
                    codex-invocation (:invocation codex-start)
                    _ (weaver/update!
                       rt (:id codex-run)
                       {:attributes {:identity/id
                                     "missing-legacy-identity"}})
                    codex-run (weaver/show rt (:id codex-run))
                    codex-outcome
                    (cancellation "legacy-codex-custody"
                                  codex-invocation)
                    codex-attachment-failure
                    (failure #(managed/validate-legacy-outcome!
                               rt codex-run
                               (assoc codex-outcome
                                      :session-usable true)))
                    codex-before [(weaver/show rt (:id codex-run))
                                  (count (weaver/list rt))]
                    codex-stale
                    (failure #(harnesses/settle-outcome!
                               rt (:id codex-run)
                               (assoc codex-outcome :invocation "stale")
                               evidence))
                    codex-after-stale
                    [(weaver/show rt (:id codex-run))
                     (count (weaver/list rt))]
                    codex-settled
                    (harnesses/settle-outcome!
                     rt (:id codex-run) codex-outcome evidence)
                    pi-start
                    (fail-unsettled
                     (legacy-create :pi "legacy-pi-custody"
                                    "legacy Pi custody"))
                    pi-run (:run pi-start)
                    pi-invocation (:invocation pi-start)
                    pi-identity-id (attr pi-run :identity/id)
                    pi-identity (identity/current rt pi-identity-id)
                    _ (weaver/update!
                       rt (:id pi-identity)
                       {:attributes {:identity/harness "codex"}})
                    pi-run (weaver/show rt (:id pi-run))
                    pi-outcome
                    (cancellation "legacy-pi-custody" pi-invocation)
                    pi-attachment-failure
                    (failure #(managed/validate-legacy-outcome!
                               rt pi-run
                               (assoc pi-outcome :session-usable true)))
                    pi-settled
                    (harnesses/settle-outcome!
                     rt (:id pi-run) pi-outcome evidence)
                    projection
                    (fn [run]
                      {:status (attr run :harness/status)
                       :error (attr run :harness/error)
                       :exit-code (attr run :harness/exit-code)
                       :session-id (attr run :harness/session-id)
                       :session-usable
                       (attr run :harness/session-usable)
                       :settled (attr run :harness/settled)
                       :settlement (attr run :harness/settlement)
                       :reservation
                       (attr run :identity/reservation-id)
                       :native-attached
                       (attr run :harness/native-attached)})]
                {:codex-attachment-failure codex-attachment-failure
                 :codex-stale codex-stale
                 :codex-stale-no-write
                 (= codex-before codex-after-stale)
                 :codex (projection codex-settled)
                 :pi-attachment-failure pi-attachment-failure
                 :pi (projection pi-settled)
                 :pi-identity-harness
                 (attr (identity/current rt pi-identity-id)
                       :identity/harness)}))]
        (testing "broken legacy identities still fail positive validation"
          (is (re-find #"does not resolve uniquely"
                       (get-in result
                               [:codex-attachment-failure :message])))
          (is (re-find #"belongs to another harness"
                       (get-in result [:pi-attachment-failure :message]))))
        (testing "negative custody remains exactly invocation-fenced"
          (is (re-find #"missing or stale invocation"
                       (get-in result [:codex-stale :message])))
          (is (true? (:codex-stale-no-write result))))
        (testing "failed unusable cancellation settles without attachment"
          (doseq [[provider session-id]
                  [[:codex "legacy-codex-custody"]
                   [:pi "legacy-pi-custody"]]]
            (is (= {:status "failed"
                    :error "primary provider failure"
                    :exit-code 143
                    :session-id session-id
                    :session-usable "false"
                    :settled "true"
                    :settlement "graceful-cancellation"
                    :reservation nil
                    :native-attached nil}
                   (provider result))))
          (is (= "codex" (:pi-identity-harness result))))))))

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
        (is (true? (:retry-session-changed result)))
        (is (= "reserved" (:retry-reserved result)))
        (is (str/includes? (:prompt result) (:retry-new result)))
        (is (not (str/includes? (:prompt result) (:retry-old result))))
        (is (str/includes? (first (:appends result)) (:retry-new result)))
        (is (str/includes? (first (:appends result)) (:run-id result)))))))

(deftest native-resume-retry-retains-frozen-settings-and-guidance
  (with-managed-world
    (fn [ctx]
      (let [result
            (eval-world
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

(ns ct.spools.harnesses.assignment-test
  "Weaver-world tests for assignment policies, readiness, and the graph bridge."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.internal.cli :as cli]
            [millstrand.test.alpha :as test-alpha]))

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")
        kanban-root (test-alpha/spool-checkout-root
                     "millhouse/spools/kanban.clj")]
    {:deps
     {'ct.spools/harnesses
      {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity
      {:local/root (.getCanonicalPath identity-root)}
      'millhouse.spools/kanban
      {:local/root (.getCanonicalPath kanban-root)}}}))

(defn with-assignment-world
  "Run a body in an isolated assignment Weaver world."
  ([f] (with-assignment-world {} f))
  ([opts f]
   (test-alpha/with-weaver-world
     [ctx (merge {:storage :sqlite-memory
                  :deps-edn (pr-str (world-deps))
                  :init-clj
                  "(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity
              :required? true})
           (runtime/module! rt :assignment-test
             {:file \"modules/assignment_test.clj\"
              :after [:identity]
              :required? true})"
                  :files
                  {"modules/assignment_test.clj"
                   "(ns modules.assignment-test
              (:require [ct.spools.harnesses :as harnesses]
                        [ct.spools.harnesses.assignment :as assignment]
                        [millhouse.spools.kanban :as kanban]
                        [millstrand.api.lifecycle.alpha :as lifecycle]))
            (lifecycle/use-resource!
             harnesses/harness-core-runtime
             kanban/kanban-runtime
             assignment/assignment-runtime)"
                   "modules/assignment_scheduler.clj"
                   "(ns modules.assignment-scheduler
              (:require [ct.spools.harnesses.execution :as execution]
                        [millstrand.api.millstrand.alpha :as millstrand]))
            (millstrand/use-handler! execution/on-event)"}} opts)]
     (f ctx))))

(def setup
  "Forms installed before each assignment world assertion."
  '(do
     (require '[clojure.string :as str]
              '[ct.spools.harnesses :as harnesses]
              '[ct.spools.harnesses.assignment :as assignment]
              '[ct.spools.harnesses.providers.pi :as pi]
              '[ct.spools.harnesses.providers.codex :as codex]
              '[ct.spools.harnesses.execution :as execution]
              '[ct.spools.harnesses.internal.assignment :as assignment-internal]
              '[ct.spools.harnesses.internal.process-custody :as custody]
              '[millhouse.spools.kanban :as kanban]
              '[millstrand.api.current.alpha :as current]
              '[millstrand.api.graph.alpha :as graph]
              '[millstrand.api.runtime.alpha :as runtime]
              '[millstrand.api.spool.alpha :as spool]
              '[millstrand.api.weaver.alpha :as weaver]
              '[millstrand.test.alpha :as test-alpha])
     (def rt (current/runtime))
     (harnesses/register-harness!
      rt :fake
      {:modes #{:headless :interactive}
       :prepare 'ct.spools.harnesses/create!
       :finish 'ct.spools.harnesses/finish!})
     (harnesses/register-harness! rt :pi (pi/harness rt))
     (harnesses/register-harness! rt :codex (codex/harness rt))
     (defn attr [strand key]
       (spool/attr-get strand key))
     (defn ctx-get [run k]
       (let [ctx (attr run :harness/context)]
         (or (get ctx k)
             (get ctx (keyword k))
             (get ctx (name k))
             (get ctx (keyword (str/replace (name k) "/" "."))))))
     (defn add-target!
       ([title] (add-target! title nil nil))
       ([title attrs] (add-target! title attrs nil))
       ([title attrs edges]
        (weaver/add!
         rt
         (cond-> {:title title
                  :attributes (merge {:kanban/card "true"
                                      :kanban/type "feature"
                                      :kanban/lane "pending"
                                      :body (str "Body for " title)}
                                     attrs)}
           (seq edges) (assoc :edges edges)))))
     (defn add-task! [feature-id title]
       (let [result (kanban/task-add! rt feature-id title {})]
         (weaver/show rt (get-in result [:task :id]))))
     (defn claim!
       [target-id owner & {:keys [branch worktree run-id by-identity]}]
       (kanban/claim!
        rt target-id
        (cond-> {"--owner" owner}
          branch (assoc "--branch" branch)
          worktree (assoc "--worktree" worktree)
          run-id (assoc "--run-id" run-id)
          by-identity (assoc "--by-identity" by-identity))))
     (defn assign!
       [target-id opts]
       (assignment/assign!
        rt
        (merge {:harness :fake
                :target target-id
                :cwd "/tmp/assignment-work"}
               opts)))
     (defn outgoing [run-id type]
       (mapv :to_strand_id (graph/outgoing-edges rt [run-id] type)))))

(defn eval-world
  "Evaluate an assertion body after assignment world setup."
  [ctx body]
  (test-alpha/repl! ctx (list 'do setup body)))

(deftest assign-cli-is-explicit-and-worktree-free
  (let [command (get-in cli/agent-arg-spec [:subcommands "assign"])]
    (is (some? command))
    (is (true? (get-in command [:flags :task :required?])))
    (is (true? (get-in command [:flags :cwd :required?])))
    (is (= :string (get-in command [:flags :effort :type])))
    (is (nil? (get-in command [:flags :worktree])))
    (is (= :agent (-> command :positionals first :name)))))

(deftest assign-cli-effort-overrides-provider-overlay
  (with-assignment-world
    (fn [ctx]
      (is (= ["high" "test-model"]
             (eval-world
              ctx
              '(do
                 (require '[clojure.data.json :as json]
                          '[ct.spools.harnesses.assignment.cli :as assign-cli])
                 (let [target (add-target! "Effort override")
                       summary (assign-cli/op-assign
                                rt {:agent "pi"
                                    :task (:id target)
                                    :cwd "/tmp/assignment-work"
                                    :effort "high"
                                    :attributes
                                    (json/read-str
                                     (json/write-str
                                      (merge {"harness/effort" "low"
                                              "harness/model" "test-model"}
                                             (into {} (for [n (range 7)]
                                                        [(str "harness.test/" n) n])))))})
                       run (weaver/show rt (:id summary))]
                   [(attr run :harness/effort)
                    (attr run :harness/model)]))))))))

(deftest assignment-persists-unresolved-operation-actor
  (with-assignment-world
    (fn [ctx]
      (is (= "unknown-assignment-actor"
             (eval-world
              ctx
              '(let [target (add-target! "Attributed assignment")
                     run (assign! (:id target)
                                  {:by-identity
                                   "unknown-assignment-actor"})]
                 (attr run :identity/by-identity))))))))

(deftest assignment-resource-installs-default-policies
  (with-assignment-world
    (fn [ctx]
      (is (= ["close-on-complete" "stop-on-complete"]
             (test-alpha/repl!
              ctx
              '(do
                 (require '[ct.spools.harnesses.assignment :as assignment]
                          '[millstrand.api.current.alpha :as current])
                 (mapv :name (assignment/assign-policies
                              (current/runtime))))))))))

(deftest unknown-policy-fails-before-create
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [target (add-target! "Feature F")
                    before (count (weaver/list
                                   rt [:= [:attr "harness/run"] "true"] {}))
                    thrown (try
                             (assign! (:id target) {:policy "nope"})
                             :not-thrown
                             (catch Exception e
                               (ex-message e)))
                    after (count (weaver/list
                                  rt [:= [:attr "harness/run"] "true"] {}))]
                {:thrown thrown :before before :after after}))]
        (is (re-find #"Unknown assign policy" (:thrown result)))
        (is (= (:before result) (:after result) 0))))))

(deftest closed-invalid-and-foreign-targets-fail
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [closed (weaver/add!
                            rt {:title "Closed"
                                :state "closed"
                                :attributes {:kanban/card "true"
                                             :kanban/type "feature"}})
                    epic (add-target! "Epic E" {:kanban/type "epic"})
                    run (harnesses/create!
                         rt {:harness :fake :prompt "ad hoc" :cwd "/tmp"})
                    missing (try
                              (assign! "no-such-target" {})
                              :not-thrown
                              (catch Exception e (ex-message e)))
                    closed-err (try
                                 (assign! (:id closed) {})
                                 :not-thrown
                                 (catch Exception e (ex-message e)))
                    epic-err (try
                               (assign! (:id epic) {})
                               :not-thrown
                               (catch Exception e (ex-message e)))
                    foreign-err (try
                                  (assign! (:id run) {})
                                  :not-thrown
                                  (catch Exception e (ex-message e)))]
                {:missing missing
                 :closed closed-err
                 :epic epic-err
                 :foreign foreign-err}))]
        (is (re-find #"does not exist" (:missing result)))
        (is (re-find #"closed" (:closed result)))
        (is (re-find #"invalid" (:epic result)))
        (is (re-find #"foreign" (:foreign result)))))))

(deftest blocked-target-accepted-then-ready-after-release
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [blocker (add-target! "Blocker")
                    feature (add-target!
                             "Blocked F" {}
                             [{:type "depends-on" :to (:id blocker)}])
                    run (assign! (:id feature) {})
                    before {:target-ready
                            (assignment/target-ready? rt (:id feature))
                            :launch-ready (assignment/launch-ready? rt run)
                            :owner (attr (weaver/show rt (:id feature)) :owner)
                            :depends (outgoing (:id run) "depends-on")
                            :serves (outgoing (:id run) "serves")}
                    _ (weaver/update! rt (:id blocker) {:state "closed"})
                    after {:target-ready
                           (assignment/target-ready? rt (:id feature))
                           :launch-ready
                           (assignment/launch-ready?
                            rt (weaver/show rt (:id run)))
                           :owner (attr (weaver/show rt (:id feature))
                                        :owner)}]
                {:before before
                 :after after
                 :feature (:id feature)}))]
        (is (false? (get-in result [:before :target-ready])))
        (is (false? (get-in result [:before :launch-ready])))
        (is (nil? (get-in result [:before :owner])))
        (is (= [] (get-in result [:before :depends])))
        (is (= [(:feature result)] (get-in result [:before :serves])))
        (is (true? (get-in result [:after :target-ready])))
        (is (true? (get-in result [:after :launch-ready])))
        (is (nil? (get-in result [:after :owner])))))))

(deftest independent-targets-are-both-launch-ready
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [a (add-target! "Feature A")
                    b (add-target! "Feature B")
                    run-a (assign! (:id a) {})
                    run-b (assign! (:id b) {})]
                {:ready [(assignment/launch-ready? rt run-a)
                         (assignment/launch-ready? rt run-b)]
                 :owners [(attr (weaver/show rt (:id a)) :owner)
                          (attr (weaver/show rt (:id b)) :owner)]
                 :ids [(:id run-a) (:id run-b)]}))]
        (is (= [true true] (:ready result)))
        (is (= [nil nil] (:owners result)))
        (is (= 2 (count (distinct (:ids result)))))))))

(deftest default-and-explicit-policy-are-frozen
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [a (add-target! "Default policy")
                    b (add-target! "Close policy")
                    default (assign! (:id a) {})
                    explicit (assign! (:id b) {:policy "close-on-complete"})
                    _ (assignment/register-assign-policy!
                       rt {:kind :assign-policy
                           :name :stop-on-complete
                           :text "CHANGED live registry text"})
                    again (weaver/show rt (:id default))]
                {:default-name (ctx-get default "assignment/policy")
                 :default-text (ctx-get default "assignment/policy-text")
                 :default-prompt (attr default :harness/prompt)
                 :close-name (ctx-get explicit "assignment/policy")
                 :close-text (ctx-get explicit "assignment/policy-text")
                 :close-prompt (attr explicit :harness/prompt)
                 :frozen-after-change
                 (ctx-get again "assignment/policy-text")
                 :identity (attr default :identity/id)
                 :cwd (attr default :harness/cwd)}))]
        (is (= "stop-on-complete" (:default-name result)))
        (is (str/includes? (:default-text result) "leave the assigned work target open"))
        (is (str/includes? (:default-prompt result) "leave the assigned work target open"))
        (is (str/includes? (:default-prompt result) "kanban claim"))
        (is (str/includes? (:default-prompt result) "/tmp/assignment-work"))
        (is (string? (:identity result)))
        (is (str/includes? (:default-prompt result) (:identity result)))
        (is (= "close-on-complete" (:close-name result)))
        (is (str/includes? (:close-text result) "close the assigned"))
        (is (str/includes? (:close-prompt result) "kanban finish"))
        (is (str/includes? (:frozen-after-change result)
                           "leave the assigned work target open"))
        (is (not (str/includes? (:frozen-after-change result) "CHANGED")))))))

(deftest guidance-distinguishes-first-claim-same-owner-and-handoff
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [pending (add-target! "First claim")
                    first-run (assign! (:id pending) {})
                    claimed (add-target! "Needs handoff")
                    _ (claim! (:id claimed) "current-owner"
                              :branch "current-branch")
                    handoff-run (assign! (:id claimed) {})
                    same-owner
                    (assignment-internal/build-guidance
                     {:target claimed
                      :cwd "/tmp/assignment-work"
                      :policy {:name "test" :text "Keep working."}
                      :profile {:kind "feature"
                                :lane "claimed"
                                :owner "assigned-worker"}
                      :identity "assigned-worker"
                      :run-id "same-run"
                      :current-state? true})]
                {:first-id (:id pending)
                 :first-worker (attr first-run :identity/id)
                 :first-prompt (attr first-run :harness/prompt)
                 :handoff-id (:id claimed)
                 :handoff-worker (attr handoff-run :identity/id)
                 :handoff-prompt (attr handoff-run :harness/prompt)
                 :same-owner same-owner}))]
        (is (str/includes?
             (:first-prompt result)
             (str "strand kanban claim " (:first-id result)
                  " --owner " (:first-worker result))))
        (is (str/includes? (:first-prompt result)
                           "unowned pending feature"))
        (is (str/includes? (:handoff-prompt result)
                           "latest explicit owner is current-owner"))
        (is (str/includes? (:handoff-prompt result) "handoff/reclaim"))
        (is (not (str/includes? (:handoff-prompt result)
                                (str "strand kanban claim "
                                     (:handoff-id result)))))
        (is (str/includes? (:same-owner result)
                           "already the feature's latest explicit owner"))
        (is (str/includes? (:same-owner result)
                           "without running `kanban claim` again"))
        (is (not (str/includes? (:same-owner result)
                                "strand kanban claim")))))))

(deftest task-assignment-stays-task-scoped-and-preserves-card-history
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [feature (add-target! "Parallel feature")
                    _ (claim! (:id feature) "feature-owner"
                              :branch "feature-branch")
                    task-a (add-task! (:id feature) "Task A")
                    task-b (add-task! (:id feature) "Task B")
                    run-a (assign! (:id task-a) {:by-identity "delegator"})
                    run-b (assign! (:id task-b) {})]
                {:feature (:id feature)
                 :task-a (:id task-a)
                 :task-b (:id task-b)
                 :run-a (:id run-a)
                 :run-b (:id run-b)
                 :prompt-a (attr run-a :harness/prompt)
                 :actor (attr run-a :identity/by-identity)
                 :worker (attr run-a :identity/id)
                 :owners [(some-> (kanban/current-ownership rt (:id feature))
                                  :owner)
                          (kanban/current-ownership rt (:id task-a))
                          (kanban/current-ownership rt (:id task-b))]
                 :feature-history
                 (kanban/ownership-history rt (:id feature))
                 :serves [(outgoing (:id run-a) "serves")
                          (outgoing (:id run-b) "serves")]
                 :roots [(outgoing (:id run-a) "serves-root")
                         (outgoing (:id run-b) "serves-root")]}))]
        (is (str/includes? (:prompt-a result)
                           (str "strand show " (:task-a result))))
        (is (not (str/includes? (:prompt-a result) "strand kanban claim")))
        (is (not (str/includes? (:prompt-a result) "claim the parent feature")))
        (is (re-find #"inherited feature\s+owner feature-owner"
                     (:prompt-a result)))
        (is (= "delegator" (:actor result)))
        (is (not= (:actor result) (:worker result)))
        (is (= ["feature-owner" nil nil] (:owners result)))
        (is (= ["feature-owner"]
               (mapv :owner (:feature-history result))))
        (is (= [[(:task-a result)] [(:task-b result)]] (:serves result)))
        (is (= [[(:feature result)] [(:feature result)]] (:roots result)))
        (is (= 2 (count (distinct [(:run-a result) (:run-b result)]))))))))

(deftest assignment-and-participation-preserve-reporter-and-owner
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [card-result
                    (kanban/add! rt "Reported feature"
                                 {"--reported-by" "original-reporter"
                                  "--by-identity" "card-creator"})
                    card-id (get-in card-result [:card :id])
                    _ (claim! card-id "current-owner" :branch "owner-branch")
                    run (assign! card-id {:by-identity "delegator"})
                    worker (attr run :identity/id)
                    _ (claim! card-id worker
                              :branch "worker-branch"
                              :worktree "/tmp/assignment-work"
                              :run-id (:id run)
                              :by-identity "current-owner")
                    _ (kanban/note! rt card-id "Reviewer observation"
                                    {"--by-identity" "reviewer"})
                    view (kanban/card-view rt card-id)]
                {:reporter (:reporter view)
                 :owner (get-in view [:ownership :current :owner])
                 :history (get-in view [:ownership :history])
                 :actor (attr run :identity/by-identity)
                 :worker (attr run :identity/id)}))]
        (is (= "original-reporter" (get-in result [:reporter :identity])))
        (is (= (:worker result) (:owner result)))
        (is (= ["current-owner" (:worker result)]
               (mapv :owner (:history result))))
        (is (= "delegator" (:actor result)))
        (is (not= (:actor result) (:worker result)))))))

(deftest custom-policy-text-is-opaque-under-built-in-name
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [card (add-target! "Opaque policy")
                    _ (assignment/register-assign-policy!
                       rt {:kind :assign-policy
                           :name :close-on-complete
                           :text "Custom prose: leave the card open."})
                    run (assign! (:id card) {:policy "close-on-complete"})]
                {:context (ctx-get run "assignment/policy-text")
                 :prompt (attr run :harness/prompt)}))]
        (is (= "Custom prose: leave the card open." (:context result)))
        (is (str/includes? (:prompt result) (:context result)))
        (is (not (str/includes? (:prompt result) "strand kanban finish")))))))

(deftest explicit-appended-system-guidance-is-preserved
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [alias-guidance "Existing alias guidance."
                    explicit-guidance "Explicit caller guidance."
                    _ (harnesses/register-alias!
                       rt :guided-pi
                       {:doc "Use Pi with existing guidance."
                        :parent :pi
                        :append-system-prompt alias-guidance
                        :attributes {}})
                    card (add-target! "Explicit guidance")
                    run (assign! (:id card)
                                 {:harness :guided-pi
                                  :policy "close-on-complete"
                                  :append-system-prompt explicit-guidance})
                    prompts (attr run :harness/appended-system-prompts)
                    rendered (str/join "\n---\n" prompts)]
                {:prompts prompts
                 :rendered rendered
                 :policy-count
                 (count (re-seq #"close the assigned work target yourself"
                                rendered))
                 :alias-count (count (re-seq #"Existing alias guidance\."
                                             rendered))
                 :explicit-count
                 (count (re-seq #"Explicit caller guidance\." rendered))}))]
        (is (= 2 (count (:prompts result))))
        (is (= "Existing alias guidance." (first (:prompts result))))
        (is (str/includes? (second (:prompts result))
                           "\n\nExplicit caller guidance."))
        (is (= 1 (:policy-count result)))
        (is (= 1 (:alias-count result)))
        (is (= 1 (:explicit-count result)))
        (is (not (str/includes? (:rendered result) "#object[")))))))

(deftest run-completion-leaves-the-card-alone
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [card (add-target! "Leave open")
                    run (assign! (:id card) {:policy "close-on-complete"})
                    finished (harnesses/finish!
                              rt (:id run)
                              {:status :done
                               :exit-code 0
                               :result "done"})
                    card-after (weaver/show rt (:id card))]
                {:card-state (:state card-after)
                 :lane (attr card-after :kanban/lane)
                 :owner (attr card-after :owner)
                 :run-status (attr finished :harness/status)}))]
        (is (= "active" (:card-state result)))
        (is (= "pending" (:lane result)))
        (is (nil? (:owner result)))
        (is (= "stopped" (:run-status result)))))))

(deftest request-id-is-idempotent-and-exclusive
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [card (add-target! "One writer")
                    first (assign! (:id card) {:request-id "assign-1"})
                    _ (claim! (:id card) (attr first :identity/id)
                              :branch "assigned-worker"
                              :worktree "/tmp/assignment-work"
                              :run-id (:id first))
                    again (assign! (:id card) {:request-id "assign-1"})
                    clash (try
                            (assign! (:id card) {:request-id "assign-2"})
                            :not-thrown
                            (catch Exception e (ex-message e)))
                    mismatch (try
                               (assign! (:id card)
                                        {:request-id "assign-1"
                                         :cwd "/tmp/other"})
                               :not-thrown
                               (catch Exception e (ex-message e)))
                    runs (weaver/list
                          rt [:= [:attr "harness/run"] "true"] {})]
                {:first (:id first)
                 :again (:id again)
                 :clash clash
                 :mismatch mismatch
                 :count (count runs)
                 :history (kanban/ownership-history rt (:id card))}))]
        (is (= (:first result) (:again result)))
        (is (re-find #"active managed run" (:clash result)))
        (is (re-find #"already held" (:mismatch result)))
        (is (= 1 (:count result)))
        (is (= 1 (count (:history result))))))))

(deftest after-continues-target-and-frozen-guidance
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [card (add-target! "Continue")
                    first (assign! (:id card)
                                   {:policy "close-on-complete"
                                    :request-id "first"})
                    _ (harnesses/finish!
                       rt (:id first)
                       {:status :done :exit-code 0 :result "first"})
                    _ (claim! (:id card) (attr first :identity/id)
                              :branch "first-owner"
                              :worktree "/tmp/assignment-work"
                              :run-id (:id first))
                    _ (assignment/register-assign-policy!
                       rt {:kind :assign-policy
                           :name :close-on-complete
                           :text "CHANGED close text"})
                    second (assign! (:id card)
                                    {:after (:id first)
                                     :request-id "second"})]
                {:same-target (= (attr first :harness/target)
                                 (attr second :harness/target)
                                 (:id card))
                 :same-session (= (attr first :harness/session-id)
                                  (attr second :harness/session-id))
                 :policy (ctx-get second "assignment/policy")
                 :text (ctx-get second "assignment/policy-text")
                 :after (ctx-get second "assignment/after")
                 :logical-id (attr second :harness/logical-id)
                 :first-logical (attr first :harness/logical-id)
                 :first-worker (attr first :identity/id)
                 :second-worker (attr second :identity/id)
                 :second-prompt (attr second :harness/prompt)
                 :history (kanban/ownership-history rt (:id card))
                 :ids [(:id first) (:id second)]}))]
        (is (true? (:same-target result)))
        (is (false? (:same-session result)))
        (is (= "close-on-complete" (:policy result)))
        (is (= (first (:ids result)) (:after result)))
        (is (= (:first-logical result) (:logical-id result)))
        (is (str/includes? (:text result) "close the assigned"))
        (is (not (str/includes? (:text result) "CHANGED")))
        (is (not= (:first-worker result) (:second-worker result)))
        (is (str/includes? (:second-prompt result)
                           (str "latest explicit owner is "
                                (:first-worker result))))
        (is (str/includes? (:second-prompt result) "handoff/reclaim"))
        (is (not (str/includes? (:second-prompt result)
                                "This is an unowned pending feature")))
        (is (= 1 (count (:history result))))
        (is (= 2 (count (distinct (:ids result)))))))))

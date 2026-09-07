(ns ct.spools.harnesses.assignment-test
  "Weaver-world tests for assignment policies, readiness, and the graph bridge."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.assignment :as assignment]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.internal.cli :as cli]
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

(defn- with-assignment-world [f]
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
           (runtime/module! rt :assignment-test
             {:file \"modules/assignment_test.clj\"
              :after [:identity]
              :required? true})"
          :files
          {"modules/assignment_test.clj"
           "(ns modules.assignment-test
              (:require [ct.spools.harnesses :as harnesses]
                        [ct.spools.harnesses.assignment :as assignment]
                        [millstrand.api.lifecycle.alpha :as lifecycle]))
            (lifecycle/use-resource!
             harnesses/harness-core-runtime
             assignment/assignment-runtime)"}}]
    (f ctx)))

(def ^:private setup
  '(do
     (require '[clojure.string :as str]
              '[ct.spools.harnesses :as harnesses]
              '[ct.spools.harnesses.assignment :as assignment]
              '[ct.spools.harnesses.providers.pi :as pi]
              '[ct.spools.harnesses.execution :as execution]
              '[millstrand.api.current.alpha :as current]
              '[millstrand.api.graph.alpha :as graph]
              '[millstrand.api.spool.alpha :as spool]
              '[millstrand.api.weaver.alpha :as weaver])
     (def rt (current/runtime))
     (harnesses/register-harness!
      rt :fake
      {:modes #{:headless :interactive}
       :prepare 'ct.spools.harnesses/create!
       :finish 'ct.spools.harnesses/finish!})
     (harnesses/register-harness! rt :pi (pi/harness rt))
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

(defn- eval-world [ctx body]
  (test-alpha/repl! ctx (list 'do setup body)))

(deftest assign-cli-is-explicit-and-worktree-free
  (let [command (get-in cli/agent-arg-spec [:subcommands "assign"])]
    (is (some? command))
    (is (true? (get-in command [:flags :task :required?])))
    (is (true? (get-in command [:flags :cwd :required?])))
    (is (nil? (get-in command [:flags :worktree])))
    (is (= :agent (-> command :positionals first :name)))))

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
        (is (str/includes? (:default-text result) "leave the assigned feature open"))
        (is (str/includes? (:default-prompt result) "leave the assigned feature open"))
        (is (str/includes? (:default-prompt result) "kanban claim"))
        (is (str/includes? (:default-prompt result) "/tmp/assignment-work"))
        (is (string? (:identity result)))
        (is (str/includes? (:default-prompt result) (:identity result)))
        (is (= "close-on-complete" (:close-name result)))
        (is (str/includes? (:close-text result) "close the assigned"))
        (is (str/includes? (:close-prompt result) "kanban finish"))
        (is (str/includes? (:frozen-after-change result)
                           "leave the assigned feature open"))
        (is (not (str/includes? (:frozen-after-change result) "CHANGED")))))))

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
                 (count (re-seq #"close the assigned feature yourself"
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
                 :count (count runs)}))]
        (is (= (:first result) (:again result)))
        (is (re-find #"active managed run" (:clash result)))
        (is (re-find #"already held" (:mismatch result)))
        (is (= 1 (:count result)))))))

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
                 :ids [(:id first) (:id second)]}))]
        (is (true? (:same-target result)))
        (is (false? (:same-session result)))
        (is (= "close-on-complete" (:policy result)))
        (is (= (first (:ids result)) (:after result)))
        (is (= (:first-logical result) (:logical-id result)))
        (is (str/includes? (:text result) "close the assigned"))
        (is (not (str/includes? (:text result) "CHANGED")))
        (is (= 2 (count (distinct (:ids result)))))))))

(deftest native-resume-reapplies-all-caller-guidance-on-current-invocation
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
                    card (add-target! "Resume guidance")
                    first (assign! (:id card)
                                   {:harness :guided-pi
                                    :policy "close-on-complete"
                                    :append-system-prompt explicit-guidance})
                    first-id (:id first)
                    _ (harnesses/finish!
                       rt first-id
                       {:status :done :exit-code 0 :result "done"
                        :session-usable true})
                    _ (assignment/register-assign-policy!
                       rt {:kind :assign-policy
                           :name :close-on-complete
                           :text "Changed live policy text"})
                    resumed (harnesses/resume!
                             rt first-id {:prompt "New user primer"})
                    argv (:argv (pi/prepare rt (pi/harness rt) resumed))
                    command (str/join " " argv)]
                {:first first-id
                 :resumed (:id resumed)
                 :argv command
                 :policy-count
                 (count (re-seq #"close the assigned feature yourself" command))
                 :alias-count (count (re-seq #"Existing alias guidance\." command))
                 :explicit-count
                 (count (re-seq #"Explicit caller guidance\." command))
                 :stdin (:stdin (pi/prepare rt (pi/harness rt) resumed))}))]
        (is (= 1 (:policy-count result)))
        (is (= 1 (:alias-count result)))
        (is (= 1 (:explicit-count result)))
        (is (not (str/includes? (:argv result) "#object[")))
        (is (str/includes? (:argv result) (:resumed result)))
        (is (not (str/includes? (:argv result) (:first result))))
        (is (= "New user primer\n" (:stdin result)))))))

(deftest fresh-continuation-binds-lineage-head
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [card (add-target! "Fresh lineage")
                    parent (assign! (:id card) {:request-id "parent"})
                    _ (harnesses/finish!
                       rt (:id parent)
                       {:status :done :exit-code 0 :result "parent"})
                    child (assign! (:id card)
                                   {:after (:id parent)
                                    :request-id "child"})
                    continues (graph/outgoing-edges rt [(:id child)]
                                                    "continues")
                    edge-types (mapv :edge_type continues)
                    _ (harnesses/finish!
                       rt (:id child)
                       {:status :done :exit-code 0 :result "child"
                        :session-usable true})
                    stale-fresh (try
                                  (assign! (:id card)
                                           {:after (:id parent)
                                            :request-id "stale-fresh"})
                                  :accepted
                                  (catch Exception e (ex-message e)))
                    stale-native (try
                                   (harnesses/resume! rt (:id parent) {})
                                   :accepted
                                   (catch Exception e (ex-message e)))
                    selected (harnesses/resolve-resume-run
                              rt {:logical-id
                                  (attr child :harness/logical-id)})]
                {:parent (:id parent)
                 :child (:id child)
                 :after (attr child :harness/after)
                 :edge-types edge-types
                 :continues continues
                 :parent-logical (attr parent :harness/logical-id)
                 :child-logical (attr child :harness/logical-id)
                 :continued (attr parent :harness/continued)
                 :stale-fresh stale-fresh
                 :stale-native stale-native
                 :selected (:id selected)}))]
        (is (= (:parent result) (:after result)))
        (is (some #{"continues"} (:edge-types result)))
        (is (re-find #"already has an accepted continuation"
                     (:stale-fresh result)))
        (is (re-find #"already has an accepted continuation"
                     (:stale-native result)))
        (is (= (:child result) (:selected result)))))))

(deftest concurrent-schedulers-and-stale-workers-cannot-share-ownership
  (let [run {:id "run-1"}
        in-flight (atom #{})
        ready-entered (java.util.concurrent.CountDownLatch. 2)
        ready-release (java.util.concurrent.CountDownLatch. 1)
        validation-entered (java.util.concurrent.CountDownLatch. 2)
        validation-release (java.util.concurrent.CountDownLatch. 1)
        validation-calls (atom 0)
        submissions (atom [])
        executor (reify java.util.concurrent.Executor
                   (execute [_ runnable]
                     (swap! submissions conj runnable)))
        scheduler-state {:in-flight in-flight :executor executor}]
    ;; Hold both atom validators after their pure updates have observed the
    ;; empty set. One swap must retry after the other commits.
    (set-validator! in-flight
                    (fn [_]
                      ;; Installing a validator checks the atom's current value.
                      ;; Synchronize the next two calls, which come from swaps.
                      (when (> (swap! validation-calls inc) 1)
                        (.countDown validation-entered)
                        (.await validation-release))
                      true))
    (with-redefs-fn
      {(ns-resolve 'ct.spools.harnesses.execution 'ready-headless)
       (fn [_]
         (.countDown ready-entered)
         (.await ready-release)
         [run])
       (ns-resolve 'ct.spools.harnesses.execution 'state)
       (constantly scheduler-state)}
      #(let [first-schedule (future (execution/schedule! {}))
             second-schedule (future (execution/schedule! {}))]
         (is (.await ready-entered 1 java.util.concurrent.TimeUnit/SECONDS))
         (.countDown ready-release)
         (is (.await validation-entered 1 java.util.concurrent.TimeUnit/SECONDS))
         (.countDown validation-release)
         (let [results [@first-schedule @second-schedule]]
           (is (= 1 (count (filter seq results))))
           (is (= 1 (count @submissions)))
           (is (= #{"run-1"} @in-flight)))))
    (set-validator! in-flight nil))
  (let [run-id "stale-run"
        phase (atom "ready")
        in-flight (atom #{})
        queued (atom [])
        ready-calls (atom 0)
        attempts (atom 0)
        failures (atom 0)
        executor (reify java.util.concurrent.Executor
                   (execute [_ runnable]
                     (swap! queued conj runnable)))
        scheduler-state {:in-flight in-flight :executor executor}]
    (with-redefs-fn
      {(ns-resolve 'ct.spools.harnesses.execution 'ready-headless)
       (fn [_]
         (if (= 1 (swap! ready-calls inc))
           [{:id run-id}]
           []))
       (ns-resolve 'ct.spools.harnesses.execution 'state)
       (constantly scheduler-state)
       (ns-resolve 'ct.spools.harnesses.execution 'full-run)
       (fn [_ _]
         {:id run-id
          :attributes {:harness/status @phase}})
       #'assignment/launch-ready? (constantly true)
       #'harnesses/begin-attempt!
       (fn [& _]
         (swap! attempts inc)
         {:attempt 1 :invocation "stolen"})
       #'harnesses/finish!
       (fn [& _]
         (swap! failures inc))
       (ns-resolve 'ct.spools.harnesses.execution 'inspect-owned!)
       (constantly nil)}
      #(do
         (is (= [run-id] (execution/schedule! {})))
         ;; Model a stale queued worker running after the prior claim was
         ;; released and another owner durably entered its attempt.
         (reset! in-flight #{})
         (reset! phase "running")
         (.run ^Runnable (first @queued))
         (is (zero? @attempts))
         (is (zero? @failures))
         (is (= "running" @phase))))))

(deftest public-scheduler-claims-only-ready-assignments
  (with-assignment-world
    (fn [ctx]
      (let [result
            (eval-world
             ctx
             '(let [blocker (add-target! "Scheduling blocker")
                    blocked (add-target!
                             "Blocked assignment" {}
                             [{:type "depends-on" :to (:id blocker)}])
                    independent (add-target! "Independent assignment")
                    blocked-run (assign! (:id blocked) {})
                    independent-run (assign! (:id independent) {})
                    opened (#'execution/activate-state! rt)
                    launched (atom [])
                    claimed
                    (try
                      (with-redefs-fn
                        {#'execution/launch-headless!
                         (fn [_ id] (swap! launched conj id))}
                        #(let [first-claimed (execution/schedule! rt)
                               _ (Thread/sleep 50)
                               _ (weaver/update! rt (:id blocker)
                                                 {:state "closed"})
                               second-claimed (execution/schedule! rt)
                               _ (Thread/sleep 50)]
                           {:first first-claimed
                            :second second-claimed}))
                      (finally ((:close-fn opened))))]
                {:claimed claimed
                 :launched @launched
                 :blocked (:id blocked-run)
                 :independent (:id independent-run)}))]
        (is (= [(:independent result)] (get-in result [:claimed :first])))
        (is (= [(:blocked result)] (get-in result [:claimed :second])))
        (is (= #{(:blocked result) (:independent result)}
               (set (:launched result))))))))

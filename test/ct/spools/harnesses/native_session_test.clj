(ns ct.spools.harnesses.native-session-test
  "Native identity registration and managed Codex lifecycle contracts."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.managed-startup-test :as fixture]))

(def setup
  "Load the native registration boundary in an isolated world."
  '(require '[ct.spools.harnesses.native-session :as native]
            '[ct.spools.harnesses.providers.codex :as codex]))

(deftest direct-registration-is-idempotent-and-not-process-custody
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx (list 'do setup
                       '(let [request {:harness "codex" :native-session-id "direct"
                                       :cwd "/tmp/direct" :model "actual-model"}
                              first (native/register! rt request)
                              before (weaver/show rt (:run-id first))
                              replay (native/register! rt request)
                              after (weaver/show rt (:run-id first))
                              child (native/register!
                                     rt (assoc request :native-session-id "child-composite"
                                               :parent-identity (:identity first)))
                              parent (identity/current rt (:identity first))]
                          {:same (= (:run-id first) (:run-id replay))
                           :unchanged (= before after)
                           :attrs (:attributes after)
                           :performed (targets parent "performed")
                           :parentage (targets parent "parent-of")
                           :child (:strand-id child)
                           :distinct (not= (:identity first) (:identity child))
                           :run-id (:run-id first)})))]
        (is (:same result))
        (is (:unchanged result))
        (is (:distinct result))
        (is (= #{(:child result)} (:parentage result)))
        (is (= #{(:run-id result)} (:performed result)))
        (is (= "unknown" (get-in result [:attrs :harness/observed-effort])))
        (is (= "actual-model" (get-in result [:attrs :harness/observed-model])))
        (is (= "external" (get-in result [:attrs :harness/ownership])))
        (doseq [key [:harness/alias :harness/effort :harness/target :harness/attempt
                     :harness/invocation :harness/settled :harness/process-handle]]
          (is (nil? (get-in result [:attrs key])) (str key)))))))

(deftest managed-startup-fences-invocation-and-preserves-native-continuity
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx (list 'do setup
                       '(let [run (harnesses/create!
                                   rt {:harness :codex :mode :interactive :cwd "/tmp/managed"
                                       :attributes {:harness/effort "low"}
                                       :append-system-prompt "Frozen policy."})
                              before-count (count (filter identity/identity? (weaver/list rt)))
                              started (harnesses/begin-attempt! rt (:id run))
                              request {:harness "codex" :native-session-id "real-thread"
                                       :cwd "/tmp/managed" :model "host-model"
                                       :run-reference (native/reference (:strand started))}
                              stale (failure #(native/register! rt (assoc request :run-reference
                                                                          (str (:id run) ":stale"))))
                              wrong-cwd (failure #(native/register! rt (assoc request :cwd "/elsewhere")))
                              attached (native/register! rt request)
                              replay (native/register! rt request)
                              finished (harnesses/finish!
                                        rt (:id run) {:status :done :exit-code 0
                                                      :invocation (:invocation started)})
                              rejected-transport (failure #(harnesses/resume!
                                                            rt (:id run) {:guidance-transport "legacy"}))
                              resumed (harnesses/resume! rt (:id run) {})
                              next-start (harnesses/begin-attempt! rt (:id resumed))
                              next-request (assoc request :run-reference
                                                  (native/reference (:strand next-start)))
                              wrong-session (failure #(native/register!
                                                       rt (assoc next-request :native-session-id "other")))
                              next-bound (native/register! rt next-request)]
                          {:rejected-transport rejected-transport
                           :before-count before-count :before-identity (attr run :identity/id)
                           :stale stale :wrong-cwd wrong-cwd :wrong-session wrong-session
                           :same (= (:identity attached) (:identity replay) (:identity next-bound))
                           :effort (:observed-effort attached)
                           :session (attr finished :harness/session-id)
                           :settled (attr finished :harness/settled)
                           :usable (attr finished :harness/session-usable)
                           :appends (attr resumed :harness/appended-system-prompts)
                           :performed (targets (identity/current rt (:identity attached)) "performed")})))]
        (is (:rejected-transport result))
        (is (zero? (:before-count result)))
        (is (nil? (:before-identity result)))
        (is (:stale result))
        (is (:wrong-cwd result))
        (is (:wrong-session result))
        (is (:same result))
        (is (= "low" (:effort result)))
        (is (= "real-thread" (:session result)))
        (is (= "true" (:settled result) (:usable result)))
        (is (= ["Frozen policy."] (:appends result)))
        (is (= 2 (count (:performed result))))))))

(deftest missing-startup-is-a-settled-bootstrap-failure-not-integration-success
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx (list 'do setup
                       '(let [run (harnesses/create! rt {:harness :codex :prompt "task" :cwd "/tmp"})
                              started (harnesses/begin-attempt! rt (:id run))
                              finished (harnesses/finish!
                                        rt (:id run) {:status :done :exit-code 0 :result "answer"
                                                      :session-id "stdout-thread" :session-usable true
                                                      :invocation (:invocation started)})
                              rejected-transport (failure #(harnesses/retry!
                                                            rt (:id run) {:guidance-transport "legacy"}))
                              retried (harnesses/retry! rt (:id run) {})
                              next-start (harnesses/begin-attempt! rt (:id run))
                              stale (failure #(native/register!
                                               rt {:harness "codex" :model "model" :cwd "/tmp"
                                                   :native-session-id "old-thread"
                                                   :run-reference (native/reference (:strand started))}))]
                          {:rejected-transport rejected-transport
                           :finished (:attributes finished) :stale stale
                           :retry-identity (attr retried :identity/id)
                           :new-invocation (not= (:invocation started) (:invocation next-start))})))]
        (is (:rejected-transport result))
        (is (= "failed" (get-in result [:finished :harness/status])))
        (is (= "bootstrap" (get-in result [:finished :harness/substatus])))
        (is (= "true" (get-in result [:finished :harness/settled])))
        (is (= "false" (get-in result [:finished :harness/session-usable])))
        (is (nil? (get-in result [:finished :identity/id])))
        (is (nil? (:retry-identity result)))
        (is (:stale result))
        (is (:new-invocation result))))))

(deftest publication-targets-launch-correlation-and-atomic-provenance
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx (list 'do setup
                       '(let [target (weaver/add! rt {:title "native target"})
                              request {:harness :codex :mode :interactive
                                       :cwd "/tmp/correlation" :target (:id target)
                                       :request-id "same-native-request"}
                              run (harnesses/create! rt request)
                              replay (harnesses/create! rt request)
                              target-conflict (failure #(harnesses/create! rt (dissoc request :request-id)))
                              launcher-path (launcher/write! rt run ["codex"] {})
                              started (harnesses/begin-attempt! rt (:id run))
                              active (:strand started)
                              _ (launcher/arm-native! rt active)
                              launcher-text (slurp launcher-path)
                              process-spec (#'execution/process-spec rt active
                                                                     {:argv ["codex"] :env {"MILLSTRAND_AGENT_ID" "hostile"}})
                              callback {:harness "codex" :native-session-id "actual"
                                        :cwd "/tmp/correlation" :model "model"
                                        :run-reference (native/reference active)}
                              before (weaver/show rt (:id run))
                              _ (reset! reject-attachment-batches? true)
                              rejected (failure #(native/register! rt callback))
                              _ (reset! reject-attachment-batches? false)
                              after (weaver/show rt (:id run))
                              attached (native/register! rt callback)
                              child-rejected (failure #(native/register!
                                                        rt (assoc callback :parent-identity (:identity attached))))
                              stopped (harnesses/create! rt {:harness :codex :mode :interactive
                                                             :cwd "/tmp/stopped"})
                              _ (harnesses/stop! rt (:id stopped) {})
                              never-started (failure #(native/register!
                                                       rt (assoc callback :cwd "/tmp/stopped"
                                                                 :run-reference (str (:id stopped) ":absent"))))]
                          {:same (= (:id run) (:id replay))
                           :target-conflict target-conflict :child-rejected child-rejected
                           :never-started never-started :rejected rejected
                           :atomic (= before after)
                           :launcher launcher-text :process-spec process-spec
                           :reference (native/reference active)
                           :performed (targets (identity/current rt (:identity attached)) "performed")
                           :run-id (:id run)})))]
        (is (:same result))
        (is (:target-conflict result))
        (is (:child-rejected result))
        (is (:never-started result))
        (is (:rejected result))
        (is (:atomic result))
        (is (= #{(:run-id result)} (:performed result)))
        (is (= (:reference result)
               (get-in result [:process-spec :env "MILLSTRAND_RUN_REFERENCE"])))
        (is (nil? (get-in result [:process-spec :env "MILLSTRAND_AGENT_ID"])))
        (is (re-find #"export MILLSTRAND_RUN_REFERENCE=" (:launcher result)))
        (is (not (re-find #"export MILLSTRAND_AGENT_ID=" (:launcher result))))))))

(deftest invalid-native-parent-does-not-publish-an-external-run
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx (list 'do setup
                       '(let [before (weaver/list rt)
                              rejected (failure #(native/register!
                                                  rt {:harness "codex" :model "model"
                                                      :native-session-id "invalid-parent-session"
                                                      :cwd "/tmp/direct"
                                                      :parent-identity "missing-parent"}))
                              after (weaver/list rt)]
                          {:rejected rejected :unchanged (= before after)})))]
        (is (:rejected result))
        (is (:unchanged result))))))

(ns ct.spools.harnesses.native-registration-test
  "Native Pi registration, publication and continuation contracts."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.managed-startup-test :as fixture]))

(deftest native-pi-managed-and-direct-registration
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [run (harnesses/create! rt {:harness :pi :mode :interactive
                                               :cwd "/tmp/native-pi"
                                               :attributes {:harness/effort "high"}
                                               :append-system-prompt "ordinary role"})
                    before (attr run :identity/id)
                    started (:strand (harnesses/begin-attempt! rt (:id run)))
                    request {:harness "pi" :native-session-id (attr run :harness/session-id)
                             :cwd "/tmp/native-pi" :run-id (:id run) :model "provider/model"}
                    bad (failure #(harnesses/register-native-session!
                                   rt (assoc request :native-session-id "wrong")))
                    uncorrelated (failure #(harnesses/register-native-session! rt (dissoc request :run-id)))
                    attached (harnesses/register-native-session! rt request)
                    replay (harnesses/register-native-session! rt request)
                    attached-run (weaver/show rt (:id run))
                    finished (harnesses/finish! rt (:id run)
                                                {:status :done :exit-code 0
                                                 :invocation (attr started :harness/invocation)})
                    resumed (harnesses/resume! rt (:id finished) {})
                    resumed-start (:strand (harnesses/begin-attempt! rt (:id resumed)))
                    resumed-native (harnesses/register-native-session!
                                    rt (assoc request :run-id (:id resumed)))
                    fork (harnesses/register-native-session!
                          rt {:harness "pi" :native-session-id "fork-session"
                              :parent-native-session-id (attr run :harness/session-id)
                              :run-id (:id resumed) :cwd "/tmp/native-pi"})
                    direct-request {:harness "pi" :native-session-id "direct" :cwd "/tmp/native-pi"}
                    direct (harnesses/register-native-session! rt direct-request)
                    direct-again (harnesses/register-native-session! rt direct-request)
                    direct-run (weaver/show rt (:run-id direct))
                    child (harnesses/register-native-session!
                           rt (assoc direct-request :native-session-id "child"
                                     :parent-identity (:identity direct) :thinking-level "low"))
                    failed-run (harnesses/create! rt {:harness :pi :mode :interactive :cwd "/tmp/native-pi"})
                    failed-start (:strand (harnesses/begin-attempt! rt (:id failed-run)))
                    missing (harnesses/finish!
                             rt (:id failed-run)
                             {:status :done :exit-code 0 :invocation (attr failed-start :harness/invocation)})]
                {:before before :bad bad :uncorrelated uncorrelated
                 :same-identity (= (:identity attached) (:identity replay) (:identity resumed-native))
                 :managed-effort (attr attached-run :harness/observed-effort)
                 :managed-appends (attr attached-run :harness/appended-system-prompts)
                 :performed (targets (identity/current rt (:identity attached)) "performed")
                 :runs [(:id run) (:id resumed)]
                 :resumed-before (attr resumed :identity/id)
                 :resumed-invocation (attr resumed-start :harness/invocation)
                 :fork-distinct (not= (:run-id fork) (:id resumed))
                 :fork-parent (contains? (targets (identity/current rt (:identity attached)) "parent-of")
                                         (:strand-id fork))
                 :direct-idempotent (= (:run-id direct) (:run-id direct-again))
                 :direct-alias (attr direct-run :harness/alias)
                 :direct-effort (attr direct-run :harness/observed-effort)
                 :direct-owner (attr direct-run :harness/ownership)
                 :direct-attempt (attr direct-run :harness/attempt)
                 :direct-settlement (attr direct-run :harness/settlement)
                 :direct-stop (failure #(harnesses/stop! rt (:id direct-run) {}))
                 :child-distinct (not= (:identity direct) (:identity child))
                 :children (targets (identity/current rt (:identity direct)) "parent-of")
                 :child-id (:strand-id child)
                 :missing-status (attr missing :harness/status)
                 :missing-reason (attr missing :harness/substatus)
                 :missing-settled (attr missing :harness/settled)
                 :missing-identity (attr missing :identity/id)}))]
        (is (nil? (:before result)))
        (is (:bad result))
        (is (:uncorrelated result))
        (is (:same-identity result))
        (is (= "high" (:managed-effort result)))
        (is (= ["ordinary role"] (:managed-appends result)))
        (is (= (set (:runs result)) (:performed result)))
        (is (nil? (:resumed-before result)))
        (is (:fork-distinct result))
        (is (:fork-parent result))
        (is (:direct-idempotent result))
        (is (nil? (:direct-alias result)))
        (is (= "unknown" (:direct-effort result)))
        (is (= "external" (:direct-owner result)))
        (is (nil? (:direct-attempt result)))
        (is (nil? (:direct-settlement result)))
        (is (:direct-stop result))
        (is (:child-distinct result))
        (is (contains? (:children result) (:child-id result)))
        (is (= "failed" (:missing-status result)))
        (is (= "bootstrap" (:missing-reason result)))
        (is (= "true" (:missing-settled result)))
        (is (nil? (:missing-identity result)))))))

(deftest rejected-native-parent-leaves-no-external-run
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [before (weaver/list rt)
                    missing (failure #(harnesses/register-native-session!
                                       rt {:harness "pi"
                                           :native-session-id "orphan-child"
                                           :cwd "/tmp/native-pi"
                                           :parent-native-session-id "unregistered-parent"}))
                    after-missing (weaver/list rt)
                    parent (harnesses/register-native-session!
                            rt {:harness "pi" :native-session-id "registered-parent"
                                :cwd "/tmp/native-pi"})
                    before-conflict (weaver/list rt)
                    conflict (failure #(harnesses/register-native-session!
                                        rt {:harness "pi"
                                            :native-session-id "conflicting-child"
                                            :cwd "/tmp/native-pi"
                                            :parent-native-session-id "registered-parent"
                                            :parent-identity "someone-else"}))
                    after-conflict (weaver/list rt)
                    matched (harnesses/register-native-session!
                             rt {:harness "pi" :native-session-id "matching-child"
                                 :cwd "/tmp/native-pi"
                                 :parent-native-session-id "registered-parent"
                                 :parent-identity (:identity parent)})]
                {:missing (:message missing)
                 :missing-unchanged (= before after-missing)
                 :parent (:identity parent)
                 :conflict (:message conflict)
                 :conflict-unchanged (= before-conflict after-conflict)
                 :matched (:identity matched)}))]
        (is (= "Native parent session is not registered uniquely"
               (:missing result)))
        (is (:missing-unchanged result))
        (is (= "Native parent identity does not match its parent session"
               (:conflict result)))
        (is (:conflict-unchanged result))
        (is (:matched result))
        (is (not= (:matched result) (:parent result)))))))

(deftest rejected-attachment-leaves-recoverable-publication
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [request {:harness "pi" :native-session-id "rejected-child"
                             :cwd "/tmp/native-pi"}
                    session-records
                    (fn []
                      {:runs (filterv #(= "rejected-child" (attr % :harness/session-id))
                                      (weaver/list rt))
                       :identities (filterv #(= "rejected-child"
                                                (attr % :identity/native-session-id))
                                            (weaver/list rt))})
                    _ (reset! reject-attachment-batches? true)
                    rejected (failure #(harnesses/register-native-session! rt request))
                    _ (reset! reject-attachment-batches? false)
                    after-rejection (session-records)
                    run (first (:runs after-rejection))
                    bound (first (:identities after-rejection))
                    completed (harnesses/register-native-session! rt request)
                    replay (harnesses/register-native-session! rt request)
                    repaired (first (:runs (session-records)))]
                {:rejected (:message rejected)
                 :runs (count (:runs after-rejection))
                 :identities (count (:identities after-rejection))
                 :phase (attr run :harness/publication-phase)
                 :outcome (attr run :harness/publication-outcome)
                 :published (attr run :harness/published)
                 :provenance (targets bound "performed")
                 :run-id (:id run)
                 :completed-run (:run-id completed)
                 :replay-run (:run-id replay)
                 :repaired-phase (attr repaired :harness/publication-phase)
                 :repaired-outcome (attr repaired :harness/publication-outcome)
                 :repaired-published (attr repaired :harness/published)
                 :repaired-effort (attr repaired :harness/observed-effort)}))]
        (is (some? (:rejected result)))
        (is (= 1 (:runs result)))
        (is (= 1 (:identities result)))
        (is (= "created" (:phase result)))
        (is (= "publishing" (:outcome result)))
        (is (nil? (:published result)))
        (is (= #{(:run-id result)} (:provenance result)))
        (is (= (:run-id result) (:completed-run result)))
        (is (= (:run-id result) (:replay-run result)))
        (is (= "complete" (:repaired-phase result)))
        (is (= "committed" (:repaired-outcome result)))
        (is (= "true" (:repaired-published result)))
        (is (= "unknown" (:repaired-effort result)))))))

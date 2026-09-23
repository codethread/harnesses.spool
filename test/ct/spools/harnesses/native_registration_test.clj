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

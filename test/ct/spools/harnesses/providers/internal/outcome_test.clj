(ns ct.spools.harnesses.providers.internal.outcome-test
  "Tests for shared native-session evidence classification."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.providers.internal.outcome :as outcome]))

(defn- evidence [attributes]
  (outcome/session-evidence (merge {:observed-id nil
                                    :known-id "provisional"
                                    :resumes? false
                                    :pinned? false
                                    :exit-code 0}
                                   attributes)))

(deftest session-evidence-classifies-what-a-run-actually-proved
  (testing "an id read from provider output is proof however the run ended"
    (is (= {:id "native-1" :usable? true :origin :observed}
           (evidence {:observed-id "native-1" :exit-code 137}))))
  (testing "a resumed run names the id an earlier run already confirmed"
    (is (= {:id "provisional" :usable? true :origin :frozen}
           (evidence {:resumes? true :exit-code 1}))))
  (testing "a pinned id is proven by a clean exit and unproven otherwise"
    (is (= {:id "provisional" :usable? true :origin :pinned}
           (evidence {:pinned? true})))
    (is (= {:id "provisional" :usable? false :origin :unproven}
           (evidence {:pinned? true :exit-code 1}))))
  (testing "an id the provider never received names no session at all"
    (is (= {:id nil :usable? false :origin :provisional}
           (evidence {:exit-code 0}))))
  (testing "a blank known id cannot stand in for evidence"
    (is (= {:id nil :usable? false :origin :provisional}
           (evidence {:known-id "" :resumes? true})))))

(deftest outcomes-emit-session-usable-directly
  (testing "done carries usable evidence straight through"
    (is (= {:status :done
            :exit-code 0
            :result "fine"
            :session-id "native-1"
            :session-usable true}
           (outcome/done {:exit-code 0 :result "fine"
                          :session {:id "native-1" :usable? true :origin :observed}}))))
  (testing "failed keeps unusable evidence visible for a later decision"
    (is (= {:status :failed
            :exit-code 1
            :result "partial"
            :session-id "provisional"
            :session-usable false
            :error "boom"}
           (outcome/failed {:exit-code 1 :result "partial" :error "boom"
                            :session {:id "provisional" :usable? false
                                      :origin :unproven}}))))
  (testing "a provisional session emits usable false and no null session id"
    (is (= {:status :failed :exit-code 1 :session-usable false :error "boom"}
           (outcome/failed {:exit-code 1 :error "boom"
                            :session {:id nil :usable? false :origin :provisional}})))))

(deftest jsonl-records-keeps-whole-records-before-a-truncated-tail
  (is (= {:records [{:type "session"} {:type "message_end"}] :truncated? true}
         (outcome/jsonl-records
          "{\"type\":\"session\"}\n{\"type\":\"message_end\"}\n{\"type\":\"mess")))
  (testing "output that never decoded is reported as unusable, not truncated"
    (is (= " JSONL parse failed: {nope}"
           (outcome/undecodable-error "" (outcome/jsonl-records "{nope}")
                                      "{nope}")))))

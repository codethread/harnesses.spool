(ns ct.spools.harnesses.providers.claude-test
  "Provider-boundary tests for Claude launch preparation and JSON normalization."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.providers.claude :as claude]))

(def ^:private runtime {})
(def ^:private definition (claude/harness runtime))

(defn- run
  ([mode]
   (run mode {}))
  ([mode attributes]
   {:id "run"
    :title "Claude run"
    :state "active"
    :attributes
    (merge {:harness/mode mode
            :harness/session-id "provisional"
            :harness/prompt "Do the work"
            :identity/prompt "You are agent tidy-brave-swan."
            :harness/appended-system-prompts
            ["Review changes only." "Do not edit files."]
            :harness/model "sonnet"
            :harness/effort "adaptive"
            :harness/extra-argv ["--dangerously-skip-permissions"]}
           attributes)}))

(deftest prepare-builds-new-and-resumed-launch-specifications
  (testing "new headless runs separate system identity from prompt stdin"
    (is (= {:argv ["claude" "--print" "--output-format" "json"
                   "--session-id" "provisional"
                   "--append-system-prompt" "You are agent tidy-brave-swan."
                   "--append-system-prompt" "Review changes only."
                   "--append-system-prompt" "Do not edit files."
                   "--model" "sonnet" "--effort" "adaptive"
                   "--dangerously-skip-permissions"]
            :stdin "Do the work\n"}
           (claude/prepare runtime definition (run "headless")))))
  (testing "interactive resumes select the session and reapply pinned guidance"
    (is (= {:argv ["claude" "--resume" "provisional"
                   "--append-system-prompt" "You are agent tidy-brave-swan."
                   "--append-system-prompt" "Review changes only."
                   "--append-system-prompt" "Do not edit files."
                   "--model" "sonnet"
                   "--effort" "adaptive" "--dangerously-skip-permissions"
                   "Do the work"]
            :stdin nil}
           (claude/prepare runtime definition
                           (run "interactive" {:harness/resumes "prior"}))))))

(deftest finish-normalizes-result-and-provider-session
  (is (= {:status :done
          :exit-code 0
          :result "final"
          :session-id "session-1"
          :session-usable true}
         (claude/finish runtime definition (run "headless")
                        {:exit-code 0
                         :stdout "{\"result\":\"final\",\"session_id\":\"session-1\"}"
                         :stderr ""}))))

(deftest finish-fails-loudly-on-incomplete-success-output
  (testing "a successful process without a result becomes a normalized failure"
    (let [outcome (claude/finish runtime definition (run "headless")
                                 {:exit-code 0
                                  :stdout "{\"session_id\":\"session-1\"}"
                                  :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"no result" (:error outcome)))))
  (testing "malformed JSON becomes a normalized failure"
    (let [outcome (claude/finish runtime definition (run "headless")
                                 {:exit-code 0 :stdout "{nope}" :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"JSON parse failed" (:error outcome))))))

(deftest finish-rejects-an-error-envelope-that-carries-text
  (testing "result text under is_error is a failure, not a completed answer"
    (let [outcome (claude/finish
                   runtime definition (run "headless")
                   {:exit-code 0
                    :stdout (str "{\"is_error\":true,\"subtype\":\"error_max_turns\","
                                 "\"result\":\"ran out of turns\","
                                 "\"session_id\":\"session-1\"}")
                    :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"ran out of turns" (:error outcome)))
      (testing "the partial answer and session survive for a later resume"
        (is (= "ran out of turns" (:result outcome)))
        (is (= "session-1" (:session-id outcome)))))))

(deftest finish-preserves-native-session-through-abnormal-exits
  (testing "a nonzero exit still reports the session Claude printed"
    (let [outcome (claude/finish
                   runtime definition (run "headless")
                   {:exit-code 1
                    :stdout "{\"result\":\"partial\",\"session_id\":\"session-1\"}"
                    :stderr "boom"})]
      (is (= :failed (:status outcome)))
      (is (= "session-1" (:session-id outcome)))
      (is (= "partial" (:result outcome)))))
  (testing "an unproven pinned id is still named because Claude was given it"
    (let [outcome (claude/finish runtime definition (run "interactive")
                                 {:exit-code 130 :stdout "" :stderr "interrupted"})]
      (is (= :failed (:status outcome)))
      (is (= "provisional" (:session-id outcome))))))

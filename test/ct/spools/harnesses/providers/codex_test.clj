(ns ct.spools.harnesses.providers.codex-test
  "Provider-boundary tests for Codex launch preparation and JSONL normalization."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.providers.codex :as codex]))

(def ^:private runtime {})
(def ^:private definition (codex/harness runtime))

(defn- run
  ([mode]
   (run mode {}))
  ([mode attributes]
   {:id "run"
    :title "Codex run"
    :state "active"
    :attributes
    (merge {:harness/mode mode
            :harness/session-id "provisional"
            :harness/prompt "Do the work"
            :identity/prompt "You are agent tidy-brave-swan."
            :harness/appended-system-prompts
            ["Review changes only." "Do not edit files."]
            :harness/model "gpt-test"
            :harness/effort "low"
            :harness/extra-argv ["--skip-git-repo-check"]}
           attributes)}))

(deftest prepare-builds-new-and-resumed-launch-specifications
  (testing "new headless runs separate developer identity from prompt stdin"
    (is (= {:argv ["codex" "exec" "--json"
                   "--model" "gpt-test"
                   "--config" "model_reasoning_effort=light"
                   "--config"
                   (str "developer_instructions=\"You are agent tidy-brave-swan."
                        "\\n\\nReview changes only."
                        "\\n\\nDo not edit files.\"")
                   "--skip-git-repo-check"]
            :stdin "Do the work\n"}
           (codex/prepare runtime definition (run "headless")))))
  (testing "headless resume names the session and reapplies pinned guidance"
    (is (= {:argv ["codex" "exec" "resume" "--json"
                   "--model" "gpt-test"
                   "--config" "model_reasoning_effort=light"
                   "--config"
                   (str "developer_instructions=\"You are agent tidy-brave-swan."
                        "\\n\\nReview changes only."
                        "\\n\\nDo not edit files.\"")
                   "--skip-git-repo-check" "provisional" "-"]
            :stdin "Do the work\n"}
           (codex/prepare runtime definition
                          (run "headless" {:harness/resumes "prior"})))))
  (testing "interactive resume keeps the initial prompt in argv for the host TTY"
    (is (= {:argv ["codex" "resume"
                   "--model" "gpt-test"
                   "--config" "model_reasoning_effort=light"
                   "--config"
                   (str "developer_instructions=\"You are agent tidy-brave-swan."
                        "\\n\\nReview changes only."
                        "\\n\\nDo not edit files.\"")
                   "--skip-git-repo-check" "provisional" "Do the work"]
            :stdin nil}
           (codex/prepare runtime definition
                          (run "interactive" {:harness/resumes "prior"})))))
  (testing "new interactive runs separate developer identity from user prompt"
    (is (= {:argv ["codex"
                   "--model" "gpt-test"
                   "--config" "model_reasoning_effort=light"
                   "--config"
                   (str "developer_instructions=\"You are agent tidy-brave-swan."
                        "\\n\\nReview changes only."
                        "\\n\\nDo not edit files.\"")
                   "--skip-git-repo-check" "Do the work"]
            :stdin nil}
           (codex/prepare runtime definition (run "interactive"))))))

(deftest finish-normalizes-final-message-and-provider-session
  (let [stdout (str "{\"type\":\"thread.started\",\"thread_id\":\"thread-1\"}\n"
                    "{\"type\":\"item.completed\",\"item\":"
                    "{\"type\":\"agent_message\",\"text\":\"draft\"}}\n"
                    "{\"type\":\"item.completed\",\"item\":"
                    "{\"type\":\"agent_message\",\"text\":\"final\"}}\n")]
    (is (= {:status :done
            :exit-code 0
            :result "final"
            :session-id "thread-1"}
           (codex/finish runtime definition (run "headless")
                         {:exit-code 0 :stdout stdout :stderr ""})))))

(deftest finish-fails-loudly-on-incomplete-success-output
  (testing "a successful process without a provider thread cannot be resumed safely"
    (let [outcome (codex/finish
                   runtime definition (run "headless")
                   {:exit-code 0
                    :stdout (str "{\"type\":\"item.completed\",\"item\":"
                                 "{\"type\":\"agent_message\",\"text\":\"final\"}}\n")
                    :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"no thread id" (:error outcome)))))
  (testing "malformed JSONL becomes a normalized failure"
    (let [outcome (codex/finish runtime definition (run "headless")
                                {:exit-code 0 :stdout "{nope}\n" :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"JSONL parse failed" (:error outcome))))))

(deftest finish-preserves-native-thread-through-abnormal-exits
  (testing "a nonzero exit still reports the thread Codex announced"
    (let [outcome (codex/finish
                   runtime definition (run "headless")
                   {:exit-code 137
                    :stdout "{\"type\":\"thread.started\",\"thread_id\":\"thread-1\"}\n"
                    :stderr "killed"})]
      (is (= :failed (:status outcome)))
      (is (= "thread-1" (:session-id outcome)))))
  (testing "records before a truncated final line remain valid evidence"
    (let [outcome (codex/finish
                   runtime definition (run "headless")
                   {:exit-code 0
                    :stdout (str "{\"type\":\"thread.started\",\"thread_id\":\"thread-1\"}\n"
                                 "{\"type\":\"item.completed\",\"item\":"
                                 "{\"type\":\"agent_message\",\"text\":\"partial\"}}\n"
                                 "{\"type\":\"turn.comp")
                    :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (= "thread-1" (:session-id outcome)))
      (is (= "partial" (:result outcome)))
      (is (re-find #"truncated JSONL" (:error outcome)))))
  (testing "a new run never reports its provisional id as a native thread"
    (let [outcome (codex/finish runtime definition (run "headless")
                                {:exit-code 1 :stdout "" :stderr "boom"})]
      (is (= :failed (:status outcome)))
      (is (nil? (:session-id outcome)))))
  (testing "a resumed run keeps the frozen thread it was launched against"
    (let [outcome (codex/finish runtime definition
                                (run "interactive" {:harness/resumes "prior"})
                                {:exit-code 130 :stdout "" :stderr "interrupted"})]
      (is (= :failed (:status outcome)))
      (is (= "provisional" (:session-id outcome))))))

(deftest finish-rejects-a-failed-turn-that-already-streamed-text
  (let [outcome (codex/finish
                 runtime definition (run "headless")
                 {:exit-code 0
                  :stdout (str "{\"type\":\"thread.started\",\"thread_id\":\"thread-1\"}\n"
                               "{\"type\":\"item.completed\",\"item\":"
                               "{\"type\":\"agent_message\",\"text\":\"here is my plan\"}}\n"
                               "{\"type\":\"turn.failed\",\"error\":"
                               "{\"message\":\"usage limit reached\"}}\n")
                  :stderr ""})]
    (is (= :failed (:status outcome)))
    (is (re-find #"usage limit reached" (:error outcome)))
    (testing "the partial answer and thread survive for a later resume"
      (is (= "here is my plan" (:result outcome)))
      (is (= "thread-1" (:session-id outcome))))))

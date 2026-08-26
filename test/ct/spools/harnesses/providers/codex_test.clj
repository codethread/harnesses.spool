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
  (testing "headless resume names the provider session"
    (is (= {:argv ["codex" "exec" "resume" "--json"
                   "--model" "gpt-test"
                   "--config" "model_reasoning_effort=light"
                   "--skip-git-repo-check" "provisional" "-"]
            :stdin "Do the work\n"}
           (codex/prepare runtime definition
                          (run "headless" {:harness/resumes "prior"})))))
  (testing "interactive resume keeps the initial prompt in argv for the host TTY"
    (is (= {:argv ["codex" "resume"
                   "--model" "gpt-test"
                   "--config" "model_reasoning_effort=light"
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

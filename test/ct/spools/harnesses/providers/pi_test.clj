(ns ct.spools.harnesses.providers.pi-test
  "Provider-boundary tests for Pi launch preparation and JSONL normalization."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.providers.pi :as pi]))

(def ^:private runtime {})
(def ^:private definition (pi/harness runtime))

(defn- run
  ([mode]
   (run mode {}))
  ([mode attributes]
   {:id "run"
    :title "Pi run"
    :state "active"
    :attributes
    (merge {:harness/mode mode
            :harness/session-id "provisional"
            :harness/prompt "Do the work"
            :identity/prompt "You are agent tidy-brave-swan."
            :harness/appended-system-prompts
            ["Review changes only." "Do not edit files."]
            :harness/model "gpt-test"
            :harness/effort "adaptive"
            :harness/extra-argv ["--skip-git-repo-check"]}
           attributes)}))

(deftest prepare-builds-new-and-resumed-launch-specifications
  (testing "new headless runs separate system identity from prompt stdin"
    (is (= {:argv ["pi" "--print" "--mode" "json"
                   "--session-id" "provisional"
                   "--append-system-prompt" "You are agent tidy-brave-swan."
                   "--append-system-prompt" "Review changes only."
                   "--append-system-prompt" "Do not edit files."
                   "--model" "gpt-test" "--thinking" "adaptive"
                   "--skip-git-repo-check"]
            :stdin "Do the work\n"}
           (pi/prepare runtime definition (run "headless")))))
  (testing "resumed headless runs select the recorded Pi session"
    (is (= {:argv ["pi" "--print" "--mode" "json"
                   "--session" "provisional"
                   "--model" "gpt-test" "--thinking" "adaptive"
                   "--skip-git-repo-check"]
            :stdin "Do the work\n"}
           (pi/prepare runtime definition
                       (run "headless" {:harness/resumes "prior"})))))
  (testing "interactive runs retain a host-TTY prompt"
    (is (= {:argv ["pi" "--session-id" "provisional"
                   "--append-system-prompt" "You are agent tidy-brave-swan."
                   "--append-system-prompt" "Review changes only."
                   "--append-system-prompt" "Do not edit files."
                   "--model" "gpt-test" "--thinking" "adaptive"
                   "--skip-git-repo-check" "Do the work"]
            :stdin nil}
           (pi/prepare runtime definition (run "interactive"))))))

(deftest finish-normalizes-final-message-and-provider-session
  (let [stdout (str "{\"type\":\"session\",\"id\":\"session-1\"}\n"
                    "{\"type\":\"message_end\",\"message\":"
                    "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"draft\"}]}}\n"
                    "{\"type\":\"message_end\",\"message\":"
                    "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"final\"}]}}\n")]
    (is (= {:status :done
            :exit-code 0
            :result "final"
            :session-id "session-1"}
           (pi/finish runtime definition (run "headless")
                      {:exit-code 0 :stdout stdout :stderr ""})))))

(deftest finish-fails-loudly-on-incomplete-success-output
  (testing "a successful process without a provider session cannot be resumed safely"
    (let [outcome (pi/finish
                   runtime definition (run "headless")
                   {:exit-code 0
                    :stdout (str "{\"type\":\"message_end\",\"message\":"
                                 "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"final\"}]}}\n")
                    :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"no session id" (:error outcome)))))
  (testing "malformed JSONL becomes a normalized failure"
    (let [outcome (pi/finish runtime definition (run "headless")
                             {:exit-code 0 :stdout "{nope}\n" :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"JSONL parse failed" (:error outcome))))))

(ns ct.spools.harnesses.guidance-protocol-repair-test
  "Protocol grammar failures remain side-effect free in disposable worlds."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [ct.spools.harnesses.internal.cli :as cli]
            [ct.spools.harnesses.internal.guidance-prompt-controls :as prompt]
            [millstrand.test.alpha :as test-alpha]))

(deftest public-cli-exposes-explicit-transport-and-receipts
  (doseq [command ["run" "retry" "resume"]]
    (is (contains? (get-in cli/agent-arg-spec
                           [:subcommands command :flags])
                   :guidance-transport)))
  (is (= :string
         (get-in cli/agent-arg-spec
                 [:subcommands "startup" :flags :guidance :type])))
  (doseq [command ["acknowledge" "fail"]]
    (is (= :string
           (get-in cli/agent-arg-spec
                   [:subcommands "guidance" :subcommands command
                    :flags :receipt :type])))))

(deftest prompt-control-recognition-is-token-aware-and-case-sensitive
  (doseq [argv [["-c" "developer_instructions=\"x\""]
                ["--config=\"developer\\u005finstructions\"=\"x\""]
                ["-cinstructions=\"x\""]
                ["--config"
                 "profiles.\"team\".model_instructions_file=\"x\""]
                ["--config"
                 "profiles.team={model_instructions_file=\"x\",model=\"m\"}"]
                ["--config"
                 "profiles={team={\"model\\u005finstructions_file\"=\"x\"}}"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"raw provider prompt controls"
                          (prompt/reject! "codex" argv))))
  (doseq [argv [["--config=shell_environment_policy.set.instructions=\"x\""]
                ["--config=DEVELOPER_INSTRUCTIONS=\"x\""]
                ["--config" "profiles.team.instructions=\"x\""]
                ["--config" "profile.team.model_instructions_file=\"x\""]
                ["--config" "profile.team.instructions_backup=\"x\""]
                ["--config"
                 "profiles.team={model=\"m\",notice=\"model_instructions_file\"}"]
                ["--config" "shell={instructions=\"x\"}"]
                ["--other" "developer_instructions=\"x\""]
                ["--" "--config" "developer_instructions=\"x\""]]]
    (let [unchanged (vec argv)]
      (is (nil? (prompt/reject! "codex" argv)))
      (is (= unchanged argv))))
  (doseq [argv [["--system-prompt" "x"]
                ["--append-system-prompt=x"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"raw provider prompt controls"
                          (prompt/reject! "pi" argv))))
  (doseq [argv [["--SYSTEM-PROMPT" "x"]
                ["--other" "mentions --system-prompt here"]
                ["--" "--system-prompt" "x"]]]
    (let [unchanged (vec argv)]
      (is (nil? (prompt/reject! "pi" argv)))
      (is (= unchanged argv)))))

(deftest invalid-capability-unicode-precedes-publication-and-reservation
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(let [calls (atom 0)
                     count-runs
                     #(count (weaver/list rt
                                          [:= [:attr "harness/run"] "true"]
                                          {}))
                     before (count-runs)
                     error
                     (binding
                      [capability/*test-capability-profiles* [profile]
                       capability/*test-preflight-runner*
                       (fn [accepted _]
                         (swap! calls inc)
                         {:source (:preflight accepted)
                          :exit-code 0
                          :stdout "{\"schema\":\"\\u００４１\"}"
                          :stderr ""})]
                       (try
                         (harnesses/create!
                          rt {:harness :native-codex
                              :mode :headless
                              :cwd "/tmp"
                              :prompt "Invalid capability fixture"
                              :guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo failure
                           (ex-message failure))))]
                 {:error error
                  :calls @calls
                  :no-publication (= before (count-runs))
                  :no-reservation
                  (empty? (weaver/list rt
                                       [:= [:attr "identity/reservation-state"] "reserved"]
                                       {}))})))]
        (is (re-find #"invalid Unicode escape" (:error result)))
        (is (= 1 (:calls result)))
        (is (true? (:no-publication result)))
        (is (true? (:no-reservation result)))))))

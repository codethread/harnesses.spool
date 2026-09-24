(ns ct.spools.harnesses.guidance-protocol-repair-test
  "Protocol grammar failures remain side-effect free in disposable worlds."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-representation-fixture :as representation-fixture]
            [ct.spools.harnesses.internal.cli :as cli]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-prompt-controls :as prompt]
            [ct.spools.harnesses.providers.pi :as pi]))

(deftest native-providers-reject-every-explicit-transport
  (doseq [harness ["codex" "pi"]]
    (is (nil? (guidance/select! nil {:harness harness})))
    (doseq [transport ["launch" "legacy" "native-v1" "" "invalid"]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"transport selection is unsupported"
           (guidance/select! nil {:harness harness :requested transport})))))
  (doseq [harness ["claude" "cursor"]
          transport [nil "legacy"]]
    (is (nil? (guidance/select! nil {:harness harness :requested transport})))))

(deftest public-cli-exposes-explicit-transport-and-receipts
  (doseq [command ["run" "retry" "resume"]]
    (is (contains? (get-in cli/agent-arg-spec
                           [:subcommands command :flags])
                   :guidance-transport)))
  (is (not (contains? (:subcommands cli/agent-arg-spec) "startup")))
  (is (= :string
         (get-in cli/agent-arg-spec
                 [:subcommands "native-startup" :flags :run-reference :type])))
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
  (doseq [argv [["--system-prompt" "competing"]
                ["--append-system-prompt=competing"]
                ["--system-prompt"]
                ["--name" "worker" "--system-prompt" "competing"]
                ["--name=worker" "--append-system-prompt" "competing"]
                ["--name" "--" "--system-prompt" "competing"]
                ["--use-theme" "--system-prompt" "competing"]
                ["--list-models" "--append-system-prompt" "competing"]
                ["--print" "--system-prompt" "competing"]
                ["--unknown" "--system-prompt" "competing"]
                ["task" "--system-prompt" "competing"]
                ["--tui-mode" "invalid" "--system-prompt" "competing"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"raw provider prompt controls"
                          (prompt/reject! "pi" argv))))
  (doseq [argv [["--name" "--system-prompt"]
                ["-n" "--append-system-prompt"]
                ["--theme" "--system-prompt"]
                ["-t" "--append-system-prompt"]
                ["--extension" "--system-prompt"]
                ["-e" "--append-system-prompt"]
                ["--tools" "--system-prompt"]
                ["-xt" "--append-system-prompt"]
                ["--name" "one" "--name" "--system-prompt"]
                ["--name=value" "--" "--system-prompt" "literal"]
                ["--use-theme" "theme" "--" "--system-prompt"]
                ["--tui-mode" "regular" "--" "--system-prompt"]
                ["--list-models" "openai" "--" "--system-prompt"]
                ["--print" "message" "--" "--system-prompt"]
                ["--unknown" "value" "--" "--append-system-prompt"]
                ["--unknown=value" "--" "--system-prompt"]
                ["--verbose" "--" "--append-system-prompt"]
                ["--" "--system-prompt" "literal"]
                ["--SYSTEM-PROMPT" "x"]
                ["--plugin" "/tmp/system-prompt-plugin"]
                ["--provider-option" "system-prompt" "value"]]]
    (let [unchanged (vec argv)]
      (is (nil? (prompt/reject! "pi" argv)))
      (is (= unchanged argv)))))

(deftest pi-launch-preserves-owned-extra-argv-byte-for-byte
  (let [extra ["--name" "--system-prompt"
               "--unknown" "value"
               "--" "--append-system-prompt" "literal"]
        run (assoc-in (representation-fixture/run "pi" "native-v1")
                      [:attributes :harness/extra-argv] extra)
        runtime {:metadata {:config-dir "/tmp"}}
        argv (:argv (pi/prepare runtime (pi/harness runtime) run))]
    (is (= extra (vec (take-last (count extra) argv))))))

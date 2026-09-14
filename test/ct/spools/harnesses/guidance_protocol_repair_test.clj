(ns ct.spools.harnesses.guidance-protocol-repair-test
  "Protocol grammar failures remain side-effect free in disposable worlds."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [ct.spools.harnesses.internal.cli :as cli]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-prompt-controls :as prompt]
            [ct.spools.harnesses.providers.pi :as pi]
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

(deftest pi-reported-consumption-boundary-precedes-capability-execution
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"raw provider prompt controls"
       (guidance/select!
        {:metadata {:config-dir "/tmp"}}
        {:harness "pi" :requested "native-v1" :mode :headless
         :cwd "/tmp" :env {}
         :effective
         {:harness/extra-argv
          ["--name" "--" "--system-prompt" "competing"]}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"no accepted production capability"
       (guidance/select!
        {:metadata {:config-dir "/tmp"}}
        {:harness "pi" :requested "native-v1" :mode :headless
         :cwd "/tmp" :env {}
         :effective {:harness/extra-argv
                     ["--name" "--system-prompt"]}}))))

(deftest pi-launch-preserves-owned-extra-argv-byte-for-byte
  (let [extra ["--name" "--system-prompt"
               "--unknown" "value"
               "--" "--append-system-prompt" "literal"]
        run {:id "run"
             :title "Pi run"
             :state "active"
             :attributes
             {:harness/mode "headless"
              :harness/session-id "native-session"
              :harness/prompt "Main task"
              :harness/model "model"
              :harness/effort "high"
              :harness/extra-argv extra
              :harness/guidance-version 1
              :harness/guidance-transport "native-v1"
              :harness/guidance-capability {}
              :harness/guidance-capability-sha256 "capability"
              :harness/guidance-context-template {}
              :harness/guidance-context {}
              :harness/guidance-bundle-sha256 "bundle"
              :harness/guidance-attempts []}}
        argv (:argv (pi/prepare {} (pi/harness {}) run))]
    (is (= extra (vec (take-last (count extra) argv))))))

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
                     _
                     (harnesses/register-alias!
                      rt :injected-codex
                      {:doc "Disposable loader-injection profile."
                       :parent :codex
                       :env {"PATH" (.getCanonicalPath fixture-dir)
                             "OPENSSL_CONF"
                             "/tmp/unreviewed-openssl.cnf"}
                       :attributes {}})
                     injection-error
                     (binding
                      [capability/*test-capability-profiles* [profile]
                       capability/*test-preflight-runner* accepted-runner]
                       (try
                         (harnesses/create!
                          rt {:harness :injected-codex
                              :mode :headless
                              :cwd "/tmp"
                              :prompt "Injected constructor fixture"
                              :guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo failure
                           (ex-message failure))))
                     malformed-runner
                     (fn [accepted _]
                       (swap! calls inc)
                       {:source (:preflight accepted)
                        :reviewed-closure-sha256
                        (get-in accepted
                                [:process-ownership
                                 :reviewed-closure-sha256])
                        :exit-code 0
                        :stdout "{\"schema\":\"\\u００４１\"}"
                        :stderr ""})
                     create #(harnesses/create!
                              rt {:harness :native-codex
                                  :mode :headless
                                  :cwd "/tmp"
                                  :prompt "Invalid capability fixture"
                                  :guidance-transport "native-v1"})
                     error
                     (binding
                      [capability/*test-capability-profiles* [profile]
                       capability/*test-preflight-runner* malformed-runner]
                       (try (create) nil
                            (catch clojure.lang.ExceptionInfo failure
                              (ex-message failure))))
                     _ (spit preflight-file "// changed closure artifact\n")
                     closure-error
                     (binding
                      [capability/*test-capability-profiles* [profile]
                       capability/*test-preflight-runner* malformed-runner]
                       (try (create) nil
                            (catch clojure.lang.ExceptionInfo failure
                              (ex-message failure))))]
                 {:error error
                  :injection-error injection-error
                  :closure-error closure-error
                  :calls @calls
                  :no-publication (= before (count-runs))
                  :no-reservation
                  (empty? (weaver/list rt
                                       [:= [:attr "identity/reservation-state"] "reserved"]
                                       {}))})))]
        (is (re-find #"unsupported dynamic resolution inputs"
                     (:injection-error result)))
        (is (re-find #"invalid Unicode escape" (:error result)))
        (is (re-find #"artifact (size|bytes) changed"
                     (:closure-error result)))
        (is (= 1 (:calls result)))
        (is (true? (:no-publication result)))
        (is (true? (:no-reservation result)))))))

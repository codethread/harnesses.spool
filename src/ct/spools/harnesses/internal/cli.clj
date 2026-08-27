(ns ct.spools.harnesses.internal.cli
  "Static command grammar for the tracked coding-agent operation.")

(def ^:private by-identity-flag
  {:by-identity {:type :string
                 :doc "Friendly identity performing this operation."}})

(def agent-arg-spec
  "Arg-spec for the provider-neutral `agent` operation."
  {:op "agent"
   :doc "Create, await, retry, and resume tracked coding-agent runs."
   :subcommands
   {"run" {:doc "Create a tracked agent run."
           :hook-class :mutating
           :deadline-class :standard
           :flags (merge by-identity-flag
                         {:interactive
                          {:type :boolean
                           :doc "Run the agent interactively in the caller's terminal."}
                          :cwd {:type :string :doc "Execution directory."}
                          :effort {:type :string :doc "Override the agent effort."}
                          :thinking {:type :string
                                     :doc "Alias for --effort."}
                          :prompt {:type :string
                                   :doc "Prompt; required headlessly."}
                          :append-system-prompt
                          {:type :string
                           :doc "Append role or policy text to the system prompt."}
                          :title
                          {:type :string
                           :doc "Display title; defaults to the first 80 prompt characters or the agent and mode."}
                          :attributes
                          {:type :string
                           :parse :json
                           :doc "Provider overlay JSON object."}})
           :positionals [{:name :agent
                          :type :string
                          :required? true
                          :doc "Available provider harness or alias."}]}
    "await" {:doc "Wait for agent runs to reach done or failed."
             :hook-class :read
             :deadline-class :unbounded
             :flags {:timeout-secs
                     {:type :int
                      :doc "Timeout in seconds; defaults to 300."}}
             :positionals [{:name :run-ids
                            :type :string
                            :required? true
                            :variadic? true
                            :doc "Run IDs."}]}
    "retry" {:doc "Retry one failed agent run in place."
             :hook-class :mutating
             :deadline-class :standard
             :flags (merge by-identity-flag
                           {:agent
                            {:type :string
                             :doc "Replacement provider harness or alias."}
                            :cwd {:type :string :doc "Replacement cwd."}
                            :attributes
                            {:type :string
                             :parse :json
                             :doc "Provider overlay merge patch."}})
             :positionals [{:name :run-id
                            :type :string
                            :required? true
                            :doc "Failed run ID."}]}
    "resumable" {:doc "List completed interactive agent runs available for resume."
                 :hook-class :read
                 :deadline-class :standard
                 :flags by-identity-flag}
    "resume"
    {:doc "Continue a completed session selected by exactly one of run ID, native session ID, or agent identity."
     :hook-class :mutating
     :deadline-class :standard
     :flags (merge by-identity-flag
                   {:run-id {:type :string
                             :doc "Exact completed predecessor run ID."}
                    :session-id
                    {:type :string
                     :doc "Native session's latest completed run."}
                    :identity
                    {:type :string
                     :doc "Friendly identity's latest completed run."}
                    :interactive
                    {:type :boolean
                     :doc "Continue interactively in the caller's terminal."}
                    :cwd {:type :string :doc "Replacement cwd."}
                    :prompt
                    {:type :string
                     :doc "Continuation prompt; required headlessly."}
                    :title
                    {:type :string
                     :doc "Display title; defaults to the first 80 prompt characters or the agent and mode."}
                    :attributes
                    {:type :string
                     :parse :json
                     :doc "Provider overlay merge patch."}})}
    "self-complete"
    {:doc "Record best-effort result text for an interactive agent run."
     :hook-class :mutating
     :deadline-class :standard
     :flags by-identity-flag
     :positionals [{:name :run-id
                    :type :string
                    :required? true
                    :doc "Interactive agent run ID."}
                   {:name :result
                    :type :string
                    :required? true
                    :doc "Final notes."}]}
    "_started" {:doc "Private agent transition: pending to running."
                :hook-class :mutating
                :deadline-class :standard
                :positionals [{:name :run-id
                               :type :string
                               :required? true
                               :doc "Interactive agent run ID."}]}
    "_finished" {:doc "Private agent transition: record process exit."
                 :hook-class :mutating
                 :deadline-class :standard
                 :flags {:exit-code {:type :int
                                     :required? true
                                     :doc "Observed process exit code."}}
                 :positionals [{:name :run-id
                                :type :string
                                :required? true
                                :doc "Interactive agent run ID."}]}
    "list" {:doc "List available provider harnesses and resolved agent aliases."
            :hook-class :read
            :deadline-class :standard
            :flags (merge by-identity-flag
                          {:full
                           {:type :boolean
                            :doc "Return the complete visible agent registry, including unavailable entries."}})}
    "config"
    {:doc "Manage runtime-local agent availability flags."
     :subcommands
     {"list" {:doc "List runtime-local flags."
              :hook-class :read
              :deadline-class :standard}
      "set" {:doc "Set a runtime-local boolean flag."
             :hook-class :mutating
             :deadline-class :standard
             :positionals [{:name :flag
                            :type :string
                            :required? true
                            :doc "Flag name."}
                           {:name :value
                            :type :boolean-token
                            :required? true
                            :doc "Boolean value."}]}
      "unset" {:doc "Remove a runtime-local flag."
               :hook-class :mutating
               :deadline-class :standard
               :positionals [{:name :flag
                              :type :string
                              :required? true
                              :doc "Flag name."}]}}}}})

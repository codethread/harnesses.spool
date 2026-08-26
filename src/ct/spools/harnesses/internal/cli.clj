(ns ct.spools.harnesses.internal.cli
  "Static command grammar for the tracked harness operation.")

(def harness-arg-spec
  "Arg-spec for the provider-neutral `harness` operation."
  {:op "harness"
   :doc "Create, await, retry, and resume provider-neutral harness runs."
   :subcommands
   {"run" {:doc "Create an asynchronous harness run."
           :hook-class :mutating
           :deadline-class :standard
           :flags {:interactive {:type :boolean
                                 :doc "Prepare a host-TTY interactive launcher."}
                   :cwd {:type :string :doc "Execution directory."}
                   :effort {:type :string :doc "Override the alias effort."}
                   :thinking {:type :string
                              :doc "User-facing alias for --effort."}
                   :prompt {:type :string :doc "Prompt; required headlessly."}
                   :title {:type :string :doc "Run title."}
                   :attributes {:type :string
                                :parse :json
                                :doc "Provider overlay JSON object."}}
           :positionals [{:name :harness
                          :type :string
                          :required? true
                          :doc "Concrete harness or alias."}]}
    "await" {:doc "Wait for runs to reach done or failed."
             :hook-class :read
             :deadline-class :unbounded
             :flags {:timeout-secs {:type :int
                                    :doc "Timeout in seconds; defaults to 300."}}
             :positionals [{:name :run-ids
                            :type :string
                            :required? true
                            :variadic? true
                            :doc "Run IDs."}]}
    "retry" {:doc "Retry one failed run in place."
             :hook-class :mutating
             :deadline-class :standard
             :flags {:harness {:type :string :doc "Replacement alias."}
                     :cwd {:type :string :doc "Replacement cwd."}
                     :attributes {:type :string
                                  :parse :json
                                  :doc "Provider overlay merge patch."}}
             :positionals [{:name :run-id
                            :type :string
                            :required? true
                            :doc "Failed run ID."}]}
    "resumable" {:doc "List completed interactive runs available for resume."
                 :hook-class :read
                 :deadline-class :standard}
    "resume" {:doc "Create a new run continuing a completed provider session."
              :hook-class :mutating
              :deadline-class :standard
              :flags {:run-id {:type :string
                               :doc "Exact completed predecessor run ID."}
                      :session-id {:type :string
                                   :doc "Native session's latest completed run."}
                      :identity {:type :string
                                 :doc "Friendly identity's latest completed run."}
                      :interactive {:type :boolean
                                    :doc "Prepare a host-TTY interactive launcher."}
                      :cwd {:type :string :doc "Replacement cwd."}
                      :prompt {:type :string
                               :doc "Continuation prompt; required headlessly."}
                      :title {:type :string :doc "Run title."}
                      :attributes {:type :string
                                   :parse :json
                                   :doc "Provider overlay merge patch."}}}
    "self-complete" {:doc "Record best-effort interactive result text."
                     :hook-class :mutating
                     :deadline-class :standard
                     :positionals [{:name :run-id
                                    :type :string
                                    :required? true
                                    :doc "Interactive run ID."}
                                   {:name :result
                                    :type :string
                                    :required? true
                                    :doc "Final notes."}]}
    "_started" {:doc "Private wrapper transition: pending to running."
                :hook-class :mutating
                :deadline-class :standard
                :positionals [{:name :run-id
                               :type :string
                               :required? true
                               :doc "Interactive run ID."}]}
    "_finished" {:doc "Private wrapper transition: record process exit."
                 :hook-class :mutating
                 :deadline-class :standard
                 :flags {:exit-code {:type :int
                                     :required? true
                                     :doc "Observed process exit code."}}
                 :positionals [{:name :run-id
                                :type :string
                                :required? true
                                :doc "Interactive run ID."}]}
    "list" {:doc "List registered harnesses, aliases, and availability."
            :hook-class :read
            :deadline-class :standard}
    "config"
    {:doc "Manage runtime-local harness configuration."
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

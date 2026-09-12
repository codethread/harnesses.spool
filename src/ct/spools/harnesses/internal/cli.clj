(ns ct.spools.harnesses.internal.cli
  "Static command grammar for the tracked coding-agent operation."
  (:require [ct.spools.harnesses.assignment.cli :as assignment-cli]))

(def ^:private by-identity-flag
  {:by-identity {:type :string
                 :doc "Friendly identity performing this operation."}})

(def ^:private assignment-flags
  "Generic work binding recorded atomically when a run is created.

  `--target` is any strand the run serves; `--context` is durable caller data
  the run carries; `--request-id` makes the call idempotent, so a repeat of the
  same request returns the same run instead of launching a second agent."
  {:target {:type :string
            :doc "Strand this run serves."}
   :context {:type :string
             :parse :json
             :doc "Durable caller context JSON object."}
   :request-id {:type :string
                :doc "Caller idempotency key for this run request."}})

(def agent-arg-spec
  "Arg-spec for the provider-neutral `agent` operation."
  {:op "agent"
   :doc "Create, inspect, stop, retry, and resume tracked coding-agent runs."
   :subcommands
   {"assign" assignment-cli/assign-subcommand
    "reviewers"
    {:doc "List declarative reviewer lenses and current seat availability."
     :hook-class :read
     :deadline-class :standard
     :flags by-identity-flag}
    "review"
    {:doc "Capture a change and asynchronously fan it out to reviewer lenses."
     :hook-class :mutating
     :deadline-class :standard
     :flags (merge by-identity-flag
                   {:cwd {:type :string :doc "Repository path; defaults to caller cwd."}
                    :base {:type :string :doc "Explicit merge-base reference."}
                    :branch {:type :string :doc "Committed tip to review without checkout."}
                    :git {:type :string :doc "Literal unified diff or payload reference."}
                    :max-bytes {:type :int :doc "Maximum captured diff bytes (default 524288)."}
                    :agent {:type :string :repeat? true
                            :doc "Reviewer declaration name; repeat for OR."}
                    :label {:type :string :repeat? true
                            :doc "Reviewer label; repeat for OR."}})}
    "run" {:doc "Create a tracked agent run."
           :hook-class :mutating
           :deadline-class :standard
           :flags (merge by-identity-flag
                         assignment-flags
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
    "show" {:doc "Show one agent run, or the run serving a task or request."
            :hook-class :read
            :deadline-class :standard
            :flags (merge by-identity-flag
                          {:task {:type :string
                                  :doc "Show the run serving this strand."}
                           :request {:type :string
                                     :doc "Show the run holding this request id."}})
            :positionals [{:name :run-id
                           :type :string
                           :doc "Run ID."}]}
    "runs" {:doc "List agent runs compactly."
            :hook-class :read
            :deadline-class :standard
            :flags (merge by-identity-flag
                          {:active {:type :boolean
                                    :doc "Only runs that are ready or running."}
                           :task {:type :string
                                  :doc "Only runs serving this strand."}})}
    "stop" {:doc "Request a durable stop of one running agent run."
            :hook-class :mutating
            :deadline-class :standard
            :flags (merge by-identity-flag
                          {:reason {:type :string
                                    :doc "Why the run is being stopped."}})
            :positionals [{:name :run-id
                           :type :string
                           :required? true
                           :doc "Exact run ID to stop."}]}
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
    {:doc "Continue a settled native session from exactly one accepted lineage head."
     :hook-class :mutating
     :deadline-class :standard
     :flags (merge by-identity-flag
                   {:run-id {:type :string
                             :doc "Exact settled predecessor run ID."}
                    :session-id
                    {:type :string
                     :doc "Native session's latest completed run."}
                    :identity
                    {:type :string
                     :doc "Friendly identity's latest completed run."}
                    :logical-id
                    {:type :string
                     :doc "Accepted logical assignment lineage head."}
                    :interactive
                    {:type :boolean
                     :doc "Continue interactively in the caller's terminal."}
                    :prompt
                    {:type :string
                     :doc "New continuation prompt; required headlessly."}
                    :title
                    {:type :string
                     :doc "Display title; defaults to the first 80 prompt characters or the agent and mode."}
                    :request-id
                    {:type :string
                     :doc "Caller idempotency key for this continuation."}})}
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
    "_started" {:doc "Private agent transition: ready to running."
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

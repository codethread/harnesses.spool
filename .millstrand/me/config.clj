(ns me.config
  "Configure the Harnesses spool for this workspace."
  (:require [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.agent-cli :as agent-cli]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.process-custody :as process-custody]
            [ct.spools.harnesses.providers.claude :as claude]
            [ct.spools.harnesses.providers.codex :as codex]
            [ct.spools.harnesses.providers.cursor :as cursor]
            [ct.spools.harnesses.providers.pi :as pi]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(defn open-harnesses!
  "Register Pi aliases without additional command-line arguments."
  [{:keys [runtime]}]
  (let [registrations
        [(harnesses/register-alias!
          runtime :terra
          {:doc "Use the Terra model with medium thinking."
           :parent :pi
           :thinking :medium
           :attributes {:harness.pi/model "openai-codex/gpt-5.6-terra"}})
         (harnesses/register-alias!
          runtime :luna-high
          {:doc "Use the Luna model with high thinking."
           :parent :pi
           :thinking :high
           :attributes {:harness.pi/model "openai-codex/gpt-5.6-luna"}})
         (harnesses/register-alias!
          runtime :sol-low
          {:doc "Use the Sol model with low thinking."
           :parent :pi
           :thinking :low
           :attributes {:harness.pi/model "openai-codex/gpt-5.6-sol"}})
         (harnesses/register-alias!
          runtime :sol-high
          {:doc "Use the Sol model with high thinking."
           :parent :pi
           :thinking :high
           :attributes {:harness.pi/model "openai-codex/gpt-5.6-sol"}})
         (harnesses/register-alias!
          runtime :tui
          {:doc "Use the low-thinking Sol configuration for interactive work."
           :parent :sol-low
           :attributes {}})
         (harnesses/register-alias!
          runtime :reviewer
          {:doc "Use the Terra configuration for code review."
           :parent :terra
           :attributes {}})]]
    {:opened :harnesses
     :aliases (mapv :alias registrations)}))

(defn close-harnesses!
  "Remove this workspace's Pi alias registrations."
  [{:keys [runtime resource]}]
  (doseq [alias (:aliases resource)]
    (harnesses/unregister-alias! runtime alias))
  {:closed :harnesses})

(lifecycle/defresource! config-harness-runtime
  "Register current harnesses"
  {:open 'me.config/open-harnesses!
   :close 'me.config/close-harnesses!
   :after #{:harness-core-runtime}})

(millstrand/use-op! agent-cli/harness)
(millstrand/use-handler! execution/on-event)
(millstrand/use-bin! agent-cli/agent)

(lifecycle/use-resource!
 harnesses/harness-core-runtime
 claude/claude-harness-runtime
 codex/codex-harness-runtime
 cursor/cursor-harness-runtime
 pi/pi-harness-runtime
 execution/harness-execution-runtime)

(lifecycle/use-reconcile! process-custody/harness-process-custody)

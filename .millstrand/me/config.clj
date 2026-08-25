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

(defn open-pi-harness!
  "Register Pi without additional command-line arguments."
  [{:keys [runtime]}]
  (harnesses/register-harness! runtime :pi (pi/harness runtime))
  {:opened :pi})

(defn close-pi-harness!
  "Remove this workspace's Pi harness registration."
  [{:keys [runtime]}]
  (harnesses/unregister-harness! runtime :pi)
  {:closed :pi})

(lifecycle/defresource pi-harness-runtime
  "Own this workspace's argument-free Pi harness registration."
  {:open 'me.config/open-pi-harness!
   :close 'me.config/close-pi-harness!
   :after #{:harness-core-runtime}})

(millstrand/use-op! agent-cli/harness)
(millstrand/use-handler! execution/on-event)
(millstrand/use-bin! agent-cli/agent)

(lifecycle/use-resource!
 harnesses/harness-core-runtime
 claude/claude-harness-runtime
 codex/codex-harness-runtime
 cursor/cursor-harness-runtime
 pi-harness-runtime
 execution/harness-execution-runtime)

(lifecycle/use-reconcile! process-custody/harness-process-custody)

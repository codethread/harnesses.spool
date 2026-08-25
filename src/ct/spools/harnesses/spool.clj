(ns ct.spools.harnesses.spool
  "Convenience entry point that activates the complete Harnesses spool.

  Provider namespaces expose inert authoring declarations. Consumers that want
  the complete tracked-agent surface can activate this namespace; consumers
  that need a smaller surface can select declarations in their own module."
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

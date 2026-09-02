(ns ct.spools.harnesses.executors.agent.spool
  "Activate the Harnesses-backed Millhouse Workflow `:agent` adapter."
  (:require [ct.spools.harnesses.executors.agent :as agent]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(workflow/use-executor! agent/agent-stalled?)
(millstrand/use-query! agent/stalled-agent-gates)
(lifecycle/use-resource! agent/agent-engine)

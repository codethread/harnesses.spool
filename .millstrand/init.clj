(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime]
         '[ct.spools.codethread.bootstrap :as codethread])

(def runtime (current/runtime))

;; Shared Codethread activation owns agents, reviewers, and Millhouse landing.
;; Consumer modules remain explicit below; the sole :agent executor stays last.
(codethread/register! runtime)

;; https://codethread.github.io/millstrand/docs/spools/customisation/
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries
                  :required? true})

(runtime/module! runtime :millhouse/spools-workflow-all
                 {:ns 'millhouse.spools.workflow.spool
                  :after [:millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :devflow
                 {:ns 'ct.spools.devflow
                  :after [:millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :devflow/kanban-adapter
                 {:ns 'ct.spools.devflow-kanban-adapter
                  :after [:devflow
                          :millhouse/spools-kanban
                          :millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :codethread/config-help
                 {:ns 'ct.spools.codethread.help
                  :after [:millstrand/spools-batteries]
                  :required? true})

;; Repository-owned automatic delivery policy is loaded before the sole agent
;; executor so its workflow and dispatcher are present for the initial scan.
(runtime/module! runtime :harnesses/auto-run-workflows
                 {:file "me/auto_run_workflows.clj"
                  :after [:millhouse/spools-workflow-all]
                  :required? true})

(runtime/module! runtime :harnesses/auto-run
                 {:file "me/auto_run.clj"
                  :after [:harnesses/auto-run-workflows
                          :millstrand/spools-harnesses]
                  :required? true})

(codethread/register-executor!
 runtime
 [:millhouse/spools-workflow-all
  :devflow
  :devflow/kanban-adapter
  :harnesses/auto-run])

(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; https://codethread.github.io/millstrand/docs/spools/customisation/
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries
                  :spools ['millstrand.spools/batteries]})

(runtime/module! runtime :module-me-help
                 {:file "me/help.clj"
                  :spools ['millstrand.spools/batteries]
                  :after [:millstrand/spools-batteries]})

(runtime/module! runtime :millhouse/spools-identity
                 {:ns 'millhouse.spools.identity
                  :spools ['millhouse.spools/identity]
                  :required? true})

(runtime/module! runtime :millhouse/spools-kanban
                 {:ns 'millhouse.spools.kanban
                  :spools ['millhouse.spools/kanban]
                  :required? true})

(runtime/module! runtime :module-me-config
                 {:file "me/config.clj"
                  :spools ['ct.spools/harnesses 'millhouse.spools/identity]
                  :after [:millhouse/spools-identity]
                  :required? true})

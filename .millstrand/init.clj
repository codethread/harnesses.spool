(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; batteries load by default, see
;; https://codethread.github.io/millstrand/spools/batteries/ for details
;; adds common commands like `strand add` `strand list` etc
;; you can omit this `module!` and build entirely your own way, see
;; https://codethread.github.io/millstrand/docs/spools/customisation/
(runtime/module! runtime :millstrand/spools-batteries
                 {:ns 'millstrand.spools.batteries
                  :spools ['millstrand.spools/batteries]})

(runtime/module! runtime :module-me-help
                 {:file "me/help.clj"
                  :spools ['millstrand.spools/batteries]
                  :after [:millstrand/spools-batteries]})

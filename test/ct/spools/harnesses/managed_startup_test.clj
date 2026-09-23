(ns ct.spools.harnesses.managed-startup-test
  "Managed Codex/Pi reservation, startup attachment, and repair contracts."
  (:require [millstrand.test.alpha :as test-alpha]))

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    {:deps
     {'ct.spools/harnesses
      {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity
      {:local/root (.getCanonicalPath identity-root)}}}))

(defn with-managed-world
  "Run a body in an isolated managed-startup Weaver world."
  [f]
  (test-alpha/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn (pr-str (world-deps))
          :init-clj
          "(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity
              :required? true})
           (runtime/module! rt :harnesses-core
             {:file \"modules/managed_core.clj\"
              :after [:identity]
              :required? true})"
          :files
          {"modules/managed_core.clj"
           "(ns modules.managed-core
              (:require [ct.spools.harnesses :as harnesses]
                        [millstrand.api.lifecycle.alpha :as lifecycle]))
            (lifecycle/use-resource! harnesses/harness-core-runtime)"}}]
    (f ctx)))

(def setup
  "Forms installed before each managed-startup world assertion."
  '(do
     (require '[clojure.data.json :as json]
              '[ct.spools.harnesses :as harnesses]
              '[ct.spools.harnesses.execution :as execution]
              '[ct.spools.harnesses.internal.launcher :as launcher]
              '[ct.spools.harnesses.internal.managed-startup :as managed]
              '[millhouse.spools.identity :as identity]
              '[millstrand.api.current.alpha :as current]
              '[millstrand.api.graph.alpha :as graph]
              '[millstrand.api.hooks.alpha :as hooks]
              '[millstrand.api.notes.alpha :as notes]
              '[millstrand.api.spool.alpha :as spool]
              '[millstrand.api.weaver.alpha :as weaver])
     (def rt (current/runtime))
     (doseq [provider [:codex :pi :claude :cursor]]
       (harnesses/register-harness!
        rt provider
        {:modes #{:headless :interactive}
         :prepare 'ct.spools.harnesses/create!
         :finish 'ct.spools.harnesses/finish!}))
     (defn attr [strand key] (spool/attr-get strand key))
     (defn failure [f]
       (try
         (f)
         nil
         (catch clojure.lang.ExceptionInfo error
           {:message (ex-message error) :data (ex-data error)})))
     (defn targets [strand type]
       (into #{} (map :to_strand_id)
             (graph/outgoing-edges rt [(:id strand)] type)))
     (def reject-attachment-batches? (atom false))
     (defn reject-attachment-batch [ctx]
       (when (and @reject-attachment-batches?
                  (some #(contains? (:attributes %)
                                    :harness/native-attachment-source)
                        (get-in ctx [:batch/payload :strands])))
         (throw (ex-info "reject managed attachment batch"
                         {:code "test/reject-attachment"}))))
     (hooks/register-hook!
      rt :reject-managed-attachment #{:batch/apply-before-commit}
      (symbol (str (ns-name *ns*)) "reject-attachment-batch") {})))

(defn eval-world
  "Evaluate an assertion body after managed-startup world setup."
  [ctx body]
  (test-alpha/repl! ctx (list 'do setup body)))

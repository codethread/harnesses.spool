(ns ct.spools.harnesses.test-runner
  "Cold test runner for the consolidated Harnesses spool."
  (:require [clojure.test :as test]
            [ct.spools.harnesses.executors.agent-test]
            [ct.spools.harnesses.providers.claude-test]
            [ct.spools.harnesses.providers.codex-test]
            [ct.spools.harnesses.providers.cursor-test]
            [ct.spools.harnesses.providers.pi-test]
            [ct.spools.harnesses.spool-test]))

(def ^:private test-namespaces
  '[ct.spools.harnesses.executors.agent-test
    ct.spools.harnesses.providers.claude-test
    ct.spools.harnesses.providers.codex-test
    ct.spools.harnesses.providers.cursor-test
    ct.spools.harnesses.providers.pi-test
    ct.spools.harnesses.spool-test])

(defn -main
  "Run every Harnesses test namespace and exit nonzero on failure."
  [& _args]
  (let [{:keys [fail error]} (apply test/run-tests test-namespaces)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))

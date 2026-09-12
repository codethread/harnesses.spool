(ns ct.spools.harnesses.test-runner
  "Cold test runner for the consolidated Harnesses spool."
  (:require [clojure.test :as test]
            [ct.spools.harnesses.assignment-test]
            [ct.spools.harnesses.execution-assignment-test]
            [ct.spools.harnesses.executors.agent-test]
            [ct.spools.harnesses.lifecycle-test]
            [ct.spools.harnesses.providers.claude-test]
            [ct.spools.harnesses.providers.codex-test]
            [ct.spools.harnesses.providers.cursor-test]
            [ct.spools.harnesses.providers.internal.outcome-test]
            [ct.spools.harnesses.providers.pi-test]
            [ct.spools.harnesses.review-git-test]
            [ct.spools.harnesses.reviewers-test]
            [ct.spools.harnesses.spool-test]))

(def ^:private test-namespaces
  '[ct.spools.harnesses.assignment-test
    ct.spools.harnesses.executors.agent-test
    ct.spools.harnesses.lifecycle-test
    ct.spools.harnesses.providers.claude-test
    ct.spools.harnesses.providers.codex-test
    ct.spools.harnesses.providers.cursor-test
    ct.spools.harnesses.providers.internal.outcome-test
    ct.spools.harnesses.providers.pi-test
    ct.spools.harnesses.review-git-test
    ct.spools.harnesses.reviewers-test
    ct.spools.harnesses.spool-test])

(defn -main
  "Run Harnesses tests, adding external process acceptance with `--e2e`."
  [& args]
  (let [namespaces (cond-> test-namespaces
                     (some #{"--e2e"} args)
                     (conj 'ct.spools.harnesses.execution-assignment-test))
        {:keys [fail error]} (apply test/run-tests namespaces)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))

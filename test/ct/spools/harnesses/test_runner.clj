(ns ct.spools.harnesses.test-runner
  "Cold test runner for the consolidated Harnesses spool."
  (:require [clojure.test :as test]
            [ct.spools.harnesses.native-session-test]
            [ct.spools.harnesses.agent-bin-test]
            [ct.spools.harnesses.assignment-test]
            [ct.spools.harnesses.publication-test]
            [ct.spools.harnesses.assignment-concurrency-test]
            [ct.spools.harnesses.execution-assignment-test]
            [ct.spools.harnesses.executors.agent-test]
            [ct.spools.harnesses.guidance-capability-test]
            [ct.spools.harnesses.guidance-capability-deadline-test]
            [ct.spools.harnesses.guidance-closure-test]
            [ct.spools.harnesses.guidance-fixture-hygiene-test]
            [ct.spools.harnesses.guidance-process-cleanup-test]
            [ct.spools.harnesses.guidance-process-deadline-test]
            [ct.spools.harnesses.guidance-process-gate-test]
            [ct.spools.harnesses.guidance-process-identity-test]
            [ct.spools.harnesses.guidance-process-scan-test]
            [ct.spools.harnesses.guidance-provider-transport-test]
            [ct.spools.harnesses.guidance-protocol-repair-test]
            [ct.spools.harnesses.guidance-representation-deadline-test]
            [ct.spools.harnesses.guidance-representation-test]
            [ct.spools.harnesses.guidance-test]
            [ct.spools.harnesses.lifecycle-test]
            [ct.spools.harnesses.lifecycle-custody-test]
            [ct.spools.harnesses.managed-startup-test]
            [ct.spools.harnesses.native-registration-test]
            [ct.spools.harnesses.native-resume-cli-replay-test]
            [ct.spools.harnesses.providers.claude-test]
            [ct.spools.harnesses.providers.codex-test]
            [ct.spools.harnesses.providers.cursor-test]
            [ct.spools.harnesses.providers.internal.outcome-test]
            [ct.spools.harnesses.providers.pi-test]
            [ct.spools.harnesses.reconciliation-callback-test]
            [ct.spools.harnesses.reconciliation-sweep-test]
            [ct.spools.harnesses.reconciliation-test]
            [ct.spools.harnesses.review-git-test]
            [ct.spools.harnesses.reviewers-test]
            [ct.spools.harnesses.spool-test]
            [ct.spools.harnesses.strict-json-test]))

(def ^:private test-namespaces
  '[ct.spools.harnesses.native-session-test
    ct.spools.harnesses.agent-bin-test
    ct.spools.harnesses.assignment-test
    ct.spools.harnesses.publication-test
    ct.spools.harnesses.assignment-concurrency-test
    ct.spools.harnesses.executors.agent-test
    ct.spools.harnesses.guidance-capability-test
    ct.spools.harnesses.guidance-capability-deadline-test
    ct.spools.harnesses.guidance-closure-test
    ct.spools.harnesses.guidance-fixture-hygiene-test
    ct.spools.harnesses.guidance-process-cleanup-test
    ct.spools.harnesses.guidance-process-deadline-test
    ct.spools.harnesses.guidance-process-gate-test
    ct.spools.harnesses.guidance-process-identity-test
    ct.spools.harnesses.guidance-process-scan-test
    ct.spools.harnesses.guidance-provider-transport-test
    ct.spools.harnesses.guidance-protocol-repair-test
    ct.spools.harnesses.guidance-representation-deadline-test
    ct.spools.harnesses.guidance-representation-test
    ct.spools.harnesses.guidance-test
    ct.spools.harnesses.lifecycle-test
    ct.spools.harnesses.lifecycle-custody-test
    ct.spools.harnesses.managed-startup-test
    ct.spools.harnesses.native-registration-test
    ct.spools.harnesses.native-resume-cli-replay-test
    ct.spools.harnesses.providers.claude-test
    ct.spools.harnesses.providers.codex-test
    ct.spools.harnesses.providers.cursor-test
    ct.spools.harnesses.providers.internal.outcome-test
    ct.spools.harnesses.providers.pi-test
    ct.spools.harnesses.reconciliation-callback-test
    ct.spools.harnesses.reconciliation-sweep-test
    ct.spools.harnesses.reconciliation-test
    ct.spools.harnesses.review-git-test
    ct.spools.harnesses.reviewers-test
    ct.spools.harnesses.spool-test
    ct.spools.harnesses.strict-json-test])

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

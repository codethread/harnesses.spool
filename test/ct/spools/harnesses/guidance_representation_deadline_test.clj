(ns ct.spools.harnesses.guidance-representation-deadline-test
  "Required timestamp and locked admission regressions."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-representation-fixture :as fixture]
            [ct.spools.harnesses.internal.guidance :as guidance]))

(defn- failure [operation]
  (try
    (operation)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- corrupt? [run]
  (when-let [error (failure #(guidance/validate-representation! run))]
    (boolean (re-find #"corrupt partial guidance metadata"
                      (ex-message error)))))

(defn- update-record [run operation]
  (update-in run [:attributes :harness/guidance-attempts 0] operation))

(defn- acknowledged-run [harness mode acknowledged-at]
  (-> (fixture/with-pending-attempt (fixture/run harness "native-v1"))
      fixture/with-fetched-attempt
      (assoc-in [:attributes :harness/mode] mode)
      (assoc-in [:attributes :harness/guidance-attempts 0 "mode"] mode)
      (update-record #(assoc % "state" "acknowledged"
                             "acknowledged-at" acknowledged-at))))

(defn- failed-run [stage include-deadline? include-acknowledgement?]
  (let [base (cond-> (fixture/with-pending-attempt
                       (fixture/run "codex" "native-v1"))
               include-acknowledgement? fixture/with-fetched-attempt)]
    (update-record
     base
     #(cond-> (assoc %
                     "state" "failed"
                     "failure" {"stage" stage
                                "code" "fixture"
                                "diagnostic" "fixture failure"})
        (not include-deadline?)
        (assoc "no-launch"
               {"attempt" (get % "attempt")
                "invocation" (get % "invocation")
                "authority" "harness-admission/v1"})
        (not include-deadline?) (dissoc "deadline-at")
        include-acknowledgement?
        (assoc "acknowledged-at" "2026-09-13T23:59:50Z")))))

(deftest required-native-timestamps-are-present-and-well-formed
  (let [pending (fixture/with-pending-attempt
                  (fixture/run "codex" "native-v1"))
        acknowledged (acknowledged-run
                      "codex" "headless" "2026-09-13T23:59:50Z")]
    (doseq [run [(update-record pending #(assoc % "deadline-at" nil))
                 (update-record pending #(dissoc % "deadline-at"))
                 (update-record pending #(assoc % "deadline-at" "bad"))
                 (update-record acknowledged
                                #(assoc % "acknowledged-at" nil))
                 (update-record acknowledged
                                #(dissoc % "acknowledged-at"))
                 (update-record acknowledged
                                #(assoc % "acknowledged-at" "bad"))]]
      (is (corrupt? run)))))

(deftest only-genuine-preflight-failure-may-omit-its-deadline
  (is (not (corrupt? (failed-run "preflight" false false))))
  (is (corrupt? (update-record (failed-run "preflight" true false)
                               #(assoc % "deadline-at" nil))))
  (is (corrupt? (failed-run "startup" false false)))
  (is (not (corrupt? (failed-run "startup" true false))))
  (is (corrupt? (update-record (failed-run "startup" true false)
                               #(assoc % "deadline-at" nil))))
  (is (not (corrupt? (failed-run "rendering" true true))))
  (is (corrupt? (update-record (failed-run "rendering" true true)
                               #(assoc % "acknowledged-at" nil)))))

(deftest omitted-deadline-requires-scoped-no-launch-provenance
  (let [valid (failed-run "preflight" false false)]
    (doseq [run [(update-record valid #(dissoc % "no-launch"))
                 (update-record valid #(assoc-in % ["no-launch" "attempt"] 2))
                 (update-record valid #(assoc-in % ["no-launch" "invocation"]
                                                 "other-invocation"))
                 (update-record valid #(assoc-in % ["no-launch" "authority"]
                                                 "process-exit"))
                 (update-record valid #(assoc-in % ["no-launch" "settled"]
                                                 true))]]
      (is (corrupt? (assoc-in run [:attributes :harness/settlement]
                              "process-exit"))))))

(deftest no-launch-rejects-matching-durable-launch-evidence
  (let [valid (failed-run "preflight" false false)
        evidence
        [{:harness/native-attached "true"
          :harness/native-attachment-attempt 1
          :harness/native-attachment-invocation "invocation"}
         {:harness/completion-owner-invocation "invocation"}
         {:harness/provider-invocation "invocation"}
         {:harness/process-key "run/attempt-1"
          :harness/process-handle "owned-handle"}
         {:harness/settled "true"
          :harness/settlement "process-exit"
          :harness/exit-code 0}]]
    (doseq [attributes evidence]
      (is (corrupt? (update valid :attributes merge attributes))))))

(deftest prior-no-launch-attempt-does-not-conflict-with-current-evidence
  (let [historical
        (-> (failed-run "preflight" false false)
            fixture/with-retired-attempt
            (assoc-in [:attributes :harness/guidance-transport] "legacy")
            (assoc-in [:attributes :harness/attempt] 2)
            (assoc-in [:attributes :harness/invocation] nil)
            (update :attributes dissoc
                    :harness/guidance-capability
                    :harness/guidance-capability-sha256)
            (update-in [:attributes :harness/guidance-attempts]
                       conj {"attempt" 2
                             "invocation" "legacy-invocation"
                             "transport" "legacy"
                             "state" "not-required"
                             "started-at" "2026-09-14T00:00:01Z"})
            (update :attributes merge
                    {:harness/settled "true"
                     :harness/settlement "process-exit"
                     :harness/exit-code 0}))]
    (is (= "legacy" (:transport
                     (guidance/validate-representation! historical))))))

(deftest retirement-retains-attempt-scoped-launch-and-custody-evidence
  (let [run
        (update
         (failed-run "rendering" true true)
         :attributes merge
         {:harness/completion-owner-pid 101
          :harness/completion-owner-started-at "2026-09-14T00:00:02Z"
          :harness/completion-owner-host "host"
          :harness/completion-owner-invocation "invocation"
          :harness/provider-pid 102
          :harness/provider-started-at "2026-09-14T00:00:03Z"
          :harness/provider-host "host"
          :harness/provider-invocation "invocation"
          :harness/process-key "run/attempt-1"
          :harness/process-handle "owned-handle"
          :harness/settlement "process-exit"
          :harness/exit-code 0})
        retired (guidance/retire-current-attempt run)
        evidence (get (first retired) "retired-evidence")
        historical
        (-> run
            (assoc-in [:attributes :harness/guidance-attempts] retired)
            (assoc-in [:attributes :harness/guidance-transport] "legacy")
            (assoc-in [:attributes :harness/invocation] nil)
            (update :attributes dissoc
                    :harness/guidance-capability
                    :harness/guidance-capability-sha256))]
    (is (= #{"attempt" "invocation" "authority"
             "attachment-session-id" "attachment-at" "attachment-source"
             "completion-owner-pid" "completion-owner-started-at"
             "completion-owner-host" "completion-owner-invocation"
             "provider-pid" "provider-started-at" "provider-host"
             "provider-invocation" "process-key" "process-handle"
             "settlement" "exit-code"}
           (set (keys evidence))))
    (is (= "legacy"
           (:transport (guidance/validate-representation! historical))))
    (is (corrupt?
         (update-record historical #(dissoc % "retired-evidence"))))))

(deftest fetched-interactive-pi-retains-only-delayed-acknowledgement-exemption
  (let [late "2026-09-14T00:00:01Z"]
    (is (not (corrupt? (acknowledged-run "pi" "interactive" late))))
    (is (corrupt? (acknowledged-run "pi" "headless" late)))
    (is (corrupt? (acknowledged-run "codex" "interactive" late)))))

(deftest delayed-pi-requires-timely-matching-first-fetch-evidence
  (let [valid (acknowledged-run
               "pi" "interactive" "2026-09-14T00:00:01Z")]
    (doseq [run [(update-record valid #(dissoc % "first-fetch"))
                 (update-record valid #(assoc-in % ["first-fetch" "attempt"] 2))
                 (update-record valid #(assoc-in % ["first-fetch" "invocation"]
                                                 "other-invocation"))
                 (update-record valid #(assoc-in % ["first-fetch" "fetched-at"]
                                                 "2026-09-14T00:00:00Z"))
                 (update-record valid #(assoc-in % ["first-fetch" "authority"]
                                                 "adapter"))
                 (assoc-in valid [:attributes :harness/session-id]
                           "conflicting-session")
                 (assoc-in valid [:attributes :harness/native-attached-at]
                           "2026-09-13T23:59:51Z")]]
      (is (corrupt? run)))))

(deftest historical-pi-acknowledgement-keeps-its-own-origin
  (let [pi-history
        (-> (acknowledged-run "pi" "interactive" "2026-09-14T00:00:01Z")
            (update-record #(assoc %
                                   "state" "failed"
                                   "failure" {"stage" "rendering"
                                              "code" "fixture"
                                              "diagnostic" "retryable"}))
            fixture/with-retired-attempt)
        codex-retry
        (-> pi-history
            (assoc-in [:attributes :harness/harness] "codex")
            (assoc-in [:attributes :harness/mode] "headless")
            (assoc-in [:attributes :harness/guidance-transport] "legacy")
            (assoc-in [:attributes :harness/attempt] 2)
            (assoc-in [:attributes :harness/invocation] nil)
            (update :attributes dissoc
                    :harness/guidance-capability
                    :harness/guidance-capability-sha256)
            (update-in [:attributes :harness/guidance-attempts]
                       conj {"attempt" 2
                             "invocation" "codex-retry"
                             "transport" "legacy"
                             "state" "not-required"
                             "started-at" "2026-09-14T00:00:02Z"}))]
    (is (= "legacy"
           (:transport (guidance/validate-representation! codex-retry))))))

(deftest legacy-retry-selection-still-validates-native-history
  (let [selected-legacy
        (-> (fixture/with-pending-attempt (fixture/run "codex" "native-v1"))
            fixture/with-retired-attempt
            (assoc-in [:attributes :harness/guidance-transport] "legacy")
            (update :attributes dissoc
                    :harness/guidance-capability
                    :harness/guidance-capability-sha256)
            (assoc-in [:attributes :harness/invocation] nil))]
    (is (= "legacy" (:transport
                     (guidance/validate-representation! selected-legacy))))
    (is (corrupt? (update-record selected-legacy
                                 #(assoc % "deadline-at" nil))))))

(ns ct.spools.harnesses.guidance-representation-deadline-test
  "Required timestamp and locked admission regressions."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-representation-fixture :as fixture]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [millstrand.test.alpha :as test-alpha]))

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
                                              "diagnostic" "retryable"})))
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
            (assoc-in [:attributes :harness/guidance-transport] "legacy")
            (update :attributes dissoc
                    :harness/guidance-capability
                    :harness/guidance-capability-sha256)
            (assoc-in [:attributes :harness/invocation] nil))]
    (is (= "legacy" (:transport
                     (guidance/validate-representation! selected-legacy))))
    (is (corrupt? (update-record selected-legacy
                                 #(assoc % "deadline-at" nil))))))

(deftest locked-admission-reloads-and-rejects-corruption-without-writes
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(do
                 (require '[ct.spools.harnesses.catalog :as catalog])
                 (let [run
                       (harnesses/create!
                        rt {:harness :native-codex
                            :mode :headless
                            :cwd "/tmp"
                            :prompt "locked representation reload"
                            :guidance-transport "legacy"})
                       lock (catalog/publication-lock rt)
                       entered (java.util.concurrent.CountDownLatch. 1)
                       selection-calls (atom 0)
                       worker (atom nil)
                       before (atom nil)
                       error
                       (with-redefs
                        [guidance/select!
                         (fn [& _]
                           (swap! selection-calls inc)
                           (throw (ex-info "selection must not run" {})))]
                         (locking lock
                           (reset!
                            worker
                            (future
                              (.countDown entered)
                              (try
                                (harnesses/begin-attempt! rt (:id run))
                                nil
                                (catch clojure.lang.ExceptionInfo failure
                                  failure))))
                           (.await entered)
                           (weaver/update!
                            rt (:id run)
                            {:attributes
                             {:harness/attempt 1
                              :harness/invocation nil
                              :harness/native-attached "true"
                              :harness/native-attachment-attempt 1
                              :harness/native-attachment-invocation
                              "retired-native"
                              :harness/settled "true"
                              :harness/settlement "process-exit"
                              :harness/guidance-attempts
                              [{"attempt" 1
                                "invocation" "retired-native"
                                "transport" "native-v1"
                                "harness" "codex"
                                "mode" "headless"
                                "state" "failed"
                                "started-at" "2026-09-14T00:00:00Z"
                                "failure" {"stage" "preflight"
                                           "code" "fixture"
                                           "diagnostic" "attached failure"}
                                "bundle-sha256"
                                (apply str (repeat 64 "a"))
                                "capability-sha256"
                                (apply str (repeat 64 "b"))}]}})
                           (reset! before (weaver/list rt)))
                         (deref @worker 1000
                                (ex-info "admission did not finish" {})))
                       after (weaver/list rt)
                       current (weaver/show rt (:id run))]
                   {:message (ex-message error)
                    :no-write (= @before after)
                    :selection-calls @selection-calls
                    :status (attr current :harness/status)}))))]
        (is (re-find #"corrupt partial guidance metadata" (:message result)))
        (is (true? (:no-write result)))
        (is (zero? (:selection-calls result)))
        (is (= "ready" (:status result)))))))

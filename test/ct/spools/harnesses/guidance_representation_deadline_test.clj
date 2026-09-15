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
      (assoc-in [:attributes :harness/mode] mode)
      (update-record #(assoc % "state" "acknowledged"
                             "acknowledged-at" acknowledged-at))))

(defn- failed-run [stage include-deadline? include-acknowledgement?]
  (let [base (fixture/with-pending-attempt
               (fixture/run "codex" "native-v1"))]
    (update-record
     base
     #(cond-> (assoc %
                     "state" "failed"
                     "failure" {"stage" stage
                                "code" "fixture"
                                "diagnostic" "fixture failure"})
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

(deftest fetched-interactive-pi-retains-only-delayed-acknowledgement-exemption
  (let [late "2026-09-14T00:00:01Z"]
    (is (not (corrupt? (acknowledged-run "pi" "interactive" late))))
    (is (corrupt? (acknowledged-run "pi" "headless" late)))
    (is (corrupt? (acknowledged-run "codex" "interactive" late)))))

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
                           (reset!
                            before
                            (weaver/update!
                             rt (:id run)
                             {:attributes
                              {:harness/attempt 1
                               :harness/invocation nil
                               :harness/guidance-attempts
                               [{"attempt" 1
                                 "invocation" "retired-native"
                                 "transport" "native-v1"
                                 "state" "pending"
                                 "started-at" "2026-09-14T00:00:00Z"
                                 "deadline-at" nil
                                 "bundle-sha256"
                                 (apply str (repeat 64 "a"))
                                 "capability-sha256"
                                 (apply str (repeat 64 "b"))}]}})))
                         (deref @worker 1000
                                (ex-info "admission did not finish" {})))
                       after (weaver/show rt (:id run))]
                   {:message (ex-message error)
                    :no-write (= @before after)
                    :selection-calls @selection-calls
                    :status (attr after :harness/status)}))))]
        (is (re-find #"corrupt partial guidance metadata" (:message result)))
        (is (true? (:no-write result)))
        (is (zero? (:selection-calls result)))
        (is (= "ready" (:status result)))))))

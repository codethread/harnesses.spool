(ns ct.spools.harnesses.guidance-history-provenance-test
  "Locked historical-provenance admission regressions."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-fixture :as guidance-fixture]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest retired-history-corruption-rejects-before-retry-admission
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [results
            (test-alpha/repl!
             (assoc ctx :timeout-ms 60000)
             (list
              'do guidance-test/lifecycle-setup
              guidance-fixture/interactive-selection
              '(do
                 (require '[ct.spools.harnesses.catalog :as catalog]
                          '[millhouse.spools.identity :as identities])
                 (java.nio.file.Files/createSymbolicLink
                  (.toPath (java.io.File. fixture-dir "pi"))
                  (.toPath (java.io.File. "/usr/bin/true"))
                  (make-array
                   java.nio.file.attribute.FileAttribute 0))
                 (defn history-snapshot []
                   (vec (sort-by :id (weaver/list rt))))
                 (vec
                  (for [provider ["codex" "pi"]
                        corruption [:no-launch :fetch-session :fetch-time]
                        locked? [false true]]
                    (let [document
                          (if (= provider "pi")
                            pi-capability-document
                            capability-document)
                          provider-profile
                          (-> profile
                              (assoc :harness provider
                                     :capability document)
                              (assoc-in
                               [:process-ownership
                                :reviewed-closure-sha256]
                               (capability/process-ownership-sha256
                                (assoc profile
                                       :harness provider
                                       :capability document))))]
                      (binding
                       [capability/*test-capability-profiles*
                        [provider-profile]
                        capability/*test-preflight-runner*
                        (fn [accepted-profile request]
                          (assoc
                           (accepted-runner accepted-profile request)
                           :stdout
                           (strict-json/canonical-json
                            {"schema" capability/preflight-schema
                             "result" "capable"
                             "capability" document})))]
                        (let [run
                              (harnesses/create!
                               rt {:harness (str "native-" provider)
                                   :mode :headless
                                   :cwd "/tmp"
                                   :prompt "history provenance"
                                   :guidance-transport "native-v1"})
                              started
                              (harnesses/begin-attempt! rt (:id run))
                              bundle
                              (harnesses/managed-startup!
                               rt {:harness provider
                                   :native-session-id
                                   (if (= provider "pi")
                                     (attr run :harness/session-id)
                                     (str "session-" (:id run)))
                                   :cwd "/tmp"
                                   :scope "root"
                                   :bootstrap
                                   (harnesses/managed-bootstrap
                                    rt (:id run))
                                   :guidance
                                   (guidance/bootstrap
                                    (:strand started))})
                              _
                              (harnesses/guidance-fail!
                               rt (assoc (guidance-receipt bundle "failed")
                                         "stage" "preflight"))
                              exited
                              (.start
                               (ProcessBuilder.
                                ^java.util.List ["/usr/bin/true"]))
                              _
                              (harnesses/settle-outcome!
                               rt (:id run)
                               {:invocation (:invocation started)
                                :status :failed
                                :exit-code (.waitFor exited)
                                :session-usable false}
                               {:settled true
                                :settlement "process-exit"})
                              replacement
                              (if (= provider "pi") "codex" "pi")
                              retried
                              (harnesses/retry!
                               rt (:id run)
                               {:harness (str "native-" replacement)
                                :guidance-transport "legacy"})
                              original
                              (first
                               (guidance/attempt-records
                                (guidance/validation-run rt retried)))
                              corrupt-record
                              (case corruption
                                :no-launch
                                (assoc
                                 (dissoc original
                                         "deadline-at" "first-fetch")
                                 "no-launch"
                                 {"attempt" (get original "attempt")
                                  "invocation" (get original "invocation")
                                  "authority" "harness-admission/v1"})

                                :fetch-session
                                (assoc-in
                                 original
                                 ["first-fetch" "native-session-id"]
                                 "unobserved-replacement-session")

                                :fetch-time
                                (assoc-in
                                 original ["first-fetch" "fetched-at"]
                                 (str
                                  (.plusMillis
                                   (java.time.Instant/parse
                                    (get-in original
                                            ["first-fetch" "fetched-at"]))
                                   1))))
                              calls
                              (atom {:allocation 0
                                     :preflight 0
                                     :preparation 0})
                              reserve identities/reserve!
                              preflight capability/preflight!
                              codex-prepare codex/prepare
                              pi-prepare pi/prepare
                              entered
                              (java.util.concurrent.CountDownLatch. 1)
                              worker (atom nil)
                              before (atom nil)
                              mutate!
                              #(do
                                 (weaver/update!
                                  rt (:id run)
                                  {:attributes
                                   {:harness/guidance-attempts
                                    [corrupt-record]}})
                                 (reset! before (history-snapshot)))
                              invoke
                              #(try
                                 (harnesses/begin-attempt! rt (:id run))
                                 :accepted
                                 (catch Throwable error
                                   (ex-message error)))
                              result
                              (with-redefs
                               [identities/reserve!
                                (fn [& args]
                                  (swap! calls update :allocation inc)
                                  (apply reserve args))
                                capability/preflight!
                                (fn [& args]
                                  (swap! calls update :preflight inc)
                                  (apply preflight args))
                                codex/prepare
                                (fn [& args]
                                  (swap! calls update :preparation inc)
                                  (apply codex-prepare args))
                                pi/prepare
                                (fn [& args]
                                  (swap! calls update :preparation inc)
                                  (apply pi-prepare args))]
                                (if locked?
                                  (do
                                    (locking
                                     (catalog/publication-lock rt)
                                      (reset!
                                       worker
                                       (future
                                         (.countDown entered)
                                         (invoke)))
                                      (.await entered)
                                      (mutate!))
                                    (deref @worker 5000 :timeout))
                                  (do (mutate!) (invoke))))
                              after (weaver/show rt (:id run))]
                          {:provider provider
                           :corruption corruption
                           :locked? locked?
                           :result result
                           :whole-graph-unchanged
                           (= @before (history-snapshot))
                           :calls @calls
                           :attempt (attr after :harness/attempt)
                           :retired-evidence
                           (get original "retired-evidence")}))))))))]
        (is (= 12 (count results)))
        (is (every? #(re-find #"corrupt partial guidance metadata"
                              (:result %))
                    results))
        (is (every? :whole-graph-unchanged results))
        (is (every? #(= {:allocation 0 :preflight 0 :preparation 0}
                        (:calls %))
                    results))
        (is (every? #(= 1 (:attempt %)) results))
        (is (every? #(= "harness-retirement/v1"
                        (get-in % [:retired-evidence "authority"]))
                    results))))))

(deftest genuine-history-survives-both-provider-replacement-directions
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [results
            (test-alpha/repl!
             (assoc ctx :timeout-ms 45000)
             (list
              'do guidance-test/lifecycle-setup
              guidance-fixture/interactive-selection
              '(do
                 (java.nio.file.Files/createSymbolicLink
                  (.toPath (java.io.File. fixture-dir "pi"))
                  (.toPath (java.io.File. "/usr/bin/true"))
                  (make-array
                   java.nio.file.attribute.FileAttribute 0))
                 (vec
                  (for [provider ["codex" "pi"]
                        no-launch? [false true]]
                    (let [document
                          (if (= provider "pi")
                            pi-capability-document
                            capability-document)
                          provider-profile
                          (-> profile
                              (assoc :harness provider
                                     :capability document)
                              (assoc-in
                               [:process-ownership
                                :reviewed-closure-sha256]
                               (capability/process-ownership-sha256
                                (assoc profile
                                       :harness provider
                                       :capability document))))]
                      (binding
                       [capability/*test-capability-profiles*
                        [provider-profile]
                        capability/*test-preflight-runner*
                        (fn [accepted-profile request]
                          (assoc
                           (accepted-runner accepted-profile request)
                           :stdout
                           (strict-json/canonical-json
                            {"schema" capability/preflight-schema
                             "result" "capable"
                             "capability" document})))]
                        (let [run
                              (harnesses/create!
                               rt {:harness (str "native-" provider)
                                   :mode :headless
                                   :cwd "/tmp"
                                   :prompt "valid retired history"
                                   :guidance-transport "native-v1"})
                              _
                              (if no-launch?
                                (binding
                                 [capability/*test-preflight-runner*
                                  (fn [& _]
                                    (throw
                                     (ex-info
                                      "execution preflight failed" {})))]
                                  (try
                                    (harnesses/begin-attempt! rt (:id run))
                                    (catch Throwable _ nil)))
                                (let [started
                                      (harnesses/begin-attempt! rt (:id run))
                                      bundle
                                      (harnesses/managed-startup!
                                       rt {:harness provider
                                           :native-session-id
                                           (if (= provider "pi")
                                             (attr run :harness/session-id)
                                             (str "session-" (:id run)))
                                           :cwd "/tmp"
                                           :scope "root"
                                           :bootstrap
                                           (harnesses/managed-bootstrap
                                            rt (:id run))
                                           :guidance
                                           (guidance/bootstrap
                                            (:strand started))})
                                      _
                                      (harnesses/guidance-acknowledge!
                                       rt (guidance-receipt
                                           bundle "adapter-handoff"))
                                      _
                                      (harnesses/guidance-fail!
                                       rt (guidance-receipt bundle "failed"))
                                      exited
                                      (.start
                                       (ProcessBuilder.
                                        ^java.util.List ["/usr/bin/true"]))]
                                  (harnesses/settle-outcome!
                                   rt (:id run)
                                   {:invocation (:invocation started)
                                    :status :failed
                                    :exit-code (.waitFor exited)
                                    :session-usable false}
                                   {:settled true
                                    :settlement "process-exit"})))
                              replacement
                              (if (= provider "pi") "codex" "pi")
                              ready
                              (harnesses/retry!
                               rt (:id run)
                               {:harness (str "native-" replacement)
                                :guidance-transport "legacy"})
                              archived
                              (first
                               (guidance/attempt-records
                                (guidance/validation-run rt ready)))
                              second
                              (harnesses/begin-attempt! rt (:id run))
                              _
                              (harnesses/finish!
                               rt (:id run)
                               {:invocation (:invocation second)
                                :status :failed
                                :exit-code 1
                                :session-usable false
                                :evidence {:settled true
                                           :settlement "process-exit"}})
                              ready-again
                              (harnesses/retry!
                               rt (:id run)
                               {:harness (str "native-" provider)
                                :guidance-transport "legacy"})
                              third
                              (harnesses/begin-attempt! rt (:id run))
                              attempts
                              (guidance/attempt-records (:strand third))]
                          {:provider provider
                           :replacement replacement
                           :no-launch? no-launch?
                           :ready-transport
                           (:transport
                            (guidance/validate-representation!
                             (guidance/validation-run rt ready-again)))
                           :attempt (:attempt third)
                           :archived-unchanged (= archived (first attempts))
                           :retirement
                           (get archived "retired-evidence")
                           :attempts (count attempts)}))))))))]
        (is (= 4 (count results)))
        (is (every? #(= "legacy" (:ready-transport %)) results))
        (is (every? #(= 3 (:attempt %) (:attempts %)) results))
        (is (every? :archived-unchanged results))
        (is (every? #(= "harness-retirement/v1"
                        (get-in % [:retirement "authority"]))
                    results))
        (is (every?
             #(= (not (:no-launch? %))
                 (contains? (:retirement %) "attachment-session-id"))
             results))))))

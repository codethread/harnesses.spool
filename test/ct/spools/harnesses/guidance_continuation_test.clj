(ns ct.spools.harnesses.guidance-continuation-test
  "Native continuation, retry fencing, and explicit rollback tests."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-fixture :as guidance-fixture]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest pre-reservation-pi-lineage-rejects-native-before-writes
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              guidance-fixture/interactive-selection
              '(do
                 (require '[ct.spools.harnesses.internal.managed-startup
                            :as managed]
                          '[ct.spools.harnesses.providers.pi :as pi]
                          '[millhouse.spools.identity :as identity]
                          '[millstrand.api.graph.alpha :as graph])
                 (harnesses/register-harness! rt :pi (pi/harness rt))
                 (let [root
                       (with-redefs [managed/managed-harness?
                                     (constantly false)]
                         (harnesses/create!
                          rt {:harness :pi :mode :interactive
                              :cwd "/tmp/pre-reservation-pi"
                              :session-id "legacy-pi-session"}))
                       root-start
                       (harnesses/begin-attempt! rt (:id root))
                       root
                       (harnesses/finish!
                        rt (:id root)
                        {:status :done :exit-code 0
                         :session-id "legacy-pi-session"
                         :session-usable true
                         :invocation (:invocation root-start)})
                       snapshot
                       (fn [run]
                         (let [current (weaver/show rt (:id run))
                               identity-strand
                               (identity/current rt (attr current :identity/id))]
                           {:strands (->> (weaver/list rt) (map :id) sort vec)
                            :run current
                            :identity identity-strand
                            :performed
                            (->> (graph/outgoing-edges
                                  rt [(:id identity-strand)] "performed")
                                 (sort-by pr-str)
                                 vec)}))
                       before-resume (snapshot root)
                       native-resume
                       (try
                         (harnesses/resume!
                          rt (:id root)
                          {:guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo error
                           (ex-message error)))
                       after-resume (snapshot root)
                       raw-native
                       (try
                         (harnesses/create!
                          rt {:harness :pi :mode :interactive
                              :cwd "/tmp/pre-reservation-pi"
                              :session-id "legacy-pi-session"
                              :resumes (:id root)
                              :guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo error
                           (ex-message error)))
                       after-raw (snapshot root)
                       child (harnesses/resume! rt (:id root) {})
                       child-start
                       (harnesses/begin-attempt! rt (:id child))
                       child
                       (harnesses/finish!
                        rt (:id child)
                        {:status :failed :exit-code 1 :error "retry"
                         :invocation (:invocation child-start)
                         :evidence {:settled true
                                    :settlement "process-exit"}})
                       before-retry (snapshot child)
                       native-retry
                       (try
                         (harnesses/retry!
                          rt (:id child)
                          {:guidance-transport "native-v1"})
                         nil
                         (catch clojure.lang.ExceptionInfo error
                           (ex-message error)))
                       after-retry (snapshot child)]
                   {:native-resume native-resume
                    :raw-native raw-native
                    :native-retry native-retry
                    :resume-no-write (= before-resume after-resume)
                    :raw-no-write (= before-resume after-raw)
                    :retry-no-write (= before-retry after-retry)
                    :child-transport
                    (attr child :harness/guidance-transport)
                    :child-template
                    (attr child :harness/guidance-context-template)
                    :child-reservation
                    (attr child :identity/reservation-id)}))))]
        (doseq [failure [(:native-resume result)
                         (:raw-native result)
                         (:native-retry result)]]
          (is (re-find #"Pre-reservation Pi continuations require legacy"
                       failure)))
        (is (true? (:resume-no-write result)))
        (is (true? (:raw-no-write result)))
        (is (true? (:retry-no-write result)))
        (is (= "legacy" (:child-transport result)))
        (is (map? (:child-template result)))
        (is (nil? (:child-reservation result)))))))

(ns ct.spools.harnesses.managed-startup-validation-test
  "Managed continuation and positive evidence validation tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.managed-startup-test :as fixture]))

(deftest raw-legacy-pi-continuation-rejects-request-mismatches-before-writes
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [root
                    (with-redefs [managed/managed-harness? (constantly false)]
                      (harnesses/create!
                       rt {:harness :pi :mode :interactive
                           :cwd "/tmp/legacy-raw-resume"
                           :session-id "legacy-raw-native"
                           :title "legacy raw Pi root"}))
                    started (harnesses/begin-attempt! rt (:id root))
                    root (harnesses/finish!
                          rt (:id root)
                          {:status :done :exit-code 0
                           :session-id "legacy-raw-native"
                           :session-usable true
                           :invocation (:invocation started)})
                    identity-strand
                    (identity/current rt (attr root :identity/id))
                    snapshot
                    (fn []
                      {:strand-ids (->> (weaver/list rt)
                                        (map :id)
                                        sort
                                        vec)
                       :predecessor (weaver/show rt (:id root))
                       :identity (identity/current rt (attr root :identity/id))
                       :performed (->> (graph/outgoing-edges
                                        rt [(:id identity-strand)] "performed")
                                       (sort-by pr-str)
                                       vec)
                       :resumes (->> (graph/incoming-edges
                                      rt [(:id root)] "resumes")
                                     (sort-by pr-str)
                                     vec)})
                    before (snapshot)
                    provider-failure
                    (failure #(harnesses/create!
                               rt {:harness :codex :mode :interactive
                                   :cwd "/tmp/legacy-raw-resume"
                                   :session-id "legacy-raw-native"
                                   :resumes (:id root)
                                   :title "wrong legacy provider"}))
                    after-provider (snapshot)
                    session-failure
                    (failure #(harnesses/create!
                               rt {:harness :pi :mode :interactive
                                   :cwd "/tmp/legacy-raw-resume"
                                   :session-id "wrong-native-session"
                                   :resumes (:id root)
                                   :title "wrong legacy session"}))
                    after-session (snapshot)
                    missing-session-failure
                    (failure #(harnesses/create!
                               rt {:harness :pi :mode :interactive
                                   :cwd "/tmp/legacy-raw-resume"
                                   :resumes (:id root)
                                   :title "missing legacy session"}))
                    after-missing (snapshot)]
                {:provider-failure provider-failure
                 :session-failure session-failure
                 :missing-session-failure missing-session-failure
                 :provider-no-write (= before after-provider)
                 :session-no-write (= before after-session)
                 :missing-no-write (= before after-missing)}))]
        (testing "resolved provider and requested session remain exact"
          (is (re-find #"cannot change its provider"
                       (get-in result [:provider-failure :message])))
          (is (re-find #"exact explicit native session"
                       (get-in result [:session-failure :message])))
          (is (re-find #"exact explicit native session"
                       (get-in result [:missing-session-failure :message]))))
        (testing "mismatches create no child, provenance, or continuation mark"
          (is (true? (:provider-no-write result)))
          (is (true? (:session-no-write result)))
          (is (true? (:missing-no-write result))))))))

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

(deftest current-managed-continuation-mismatches-fail-before-commit-writes
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [snapshot
                    (fn [run]
                      (let [current (weaver/show rt (:id run))
                            identity-strand
                            (identity/current rt (attr current :identity/id))]
                        {:strand-ids (->> (weaver/list rt)
                                          (map :id)
                                          sort
                                          vec)
                         :predecessor current
                         :identity identity-strand
                         :performed (->> (graph/outgoing-edges
                                          rt [(:id identity-strand)]
                                          "performed")
                                         (sort-by pr-str)
                                         vec)
                         :resumes (->> (graph/incoming-edges
                                        rt [(:id current)] "resumes")
                                       (sort-by pr-str)
                                       vec)}))]
                (into {}
                      (for [provider [:codex :pi]]
                        (let [session-id (str "current-" (name provider))
                              run (harnesses/create!
                                   rt {:harness provider :mode :interactive
                                       :cwd "/tmp/current-managed-resume"
                                       :session-id session-id
                                       :title (str (name provider)
                                                   " current predecessor")})
                              started (harnesses/begin-attempt! rt (:id run))
                              bootstrap
                              (harnesses/managed-bootstrap rt (:id run))
                              _ (harnesses/managed-startup!
                                 rt {:harness (name provider)
                                     :native-session-id session-id
                                     :cwd "/tmp/current-managed-resume"
                                     :scope "root"
                                     :bootstrap bootstrap})
                              predecessor
                              (harnesses/finish!
                               rt (:id run)
                               {:status :done :exit-code 0
                                :session-id session-id
                                :session-usable true
                                :invocation (:invocation started)})
                              before (snapshot predecessor)
                              other-provider
                              (if (= :pi provider) :codex :pi)
                              provider-failure
                              (failure #(harnesses/create!
                                         rt {:harness other-provider
                                             :mode :interactive
                                             :cwd "/tmp/current-managed-resume"
                                             :session-id session-id
                                             :resumes (:id predecessor)
                                             :title "wrong current provider"}))
                              after-provider (snapshot predecessor)
                              session-failure
                              (failure #(harnesses/create!
                                         rt {:harness provider
                                             :mode :interactive
                                             :cwd "/tmp/current-managed-resume"
                                             :session-id "wrong-current-session"
                                             :resumes (:id predecessor)
                                             :title "wrong current session"}))
                              after-session (snapshot predecessor)
                              missing-failure
                              (failure #(harnesses/create!
                                         rt {:harness provider
                                             :mode :interactive
                                             :cwd "/tmp/current-managed-resume"
                                             :resumes (:id predecessor)
                                             :title "missing current session"}))
                              after-missing (snapshot predecessor)]
                          [provider
                           {:provider-failure provider-failure
                            :session-failure session-failure
                            :missing-failure missing-failure
                            :provider-no-write (= before after-provider)
                            :session-no-write (= before after-session)
                            :missing-no-write (= before after-missing)}])))))]
        (doseq [provider [:codex :pi]]
          (testing (str (name provider)
                        " continuation intent is validated before commit")
            (is (re-find #"cannot change its provider"
                         (get-in result
                                 [provider :provider-failure :message])))
            (is (re-find #"exact explicit native session"
                         (get-in result
                                 [provider :session-failure :message])))
            (is (re-find #"exact explicit native session"
                         (get-in result
                                 [provider :missing-failure :message])))
            (is (true? (get-in result [provider :provider-no-write])))
            (is (true? (get-in result [provider :session-no-write])))
            (is (true? (get-in result [provider :missing-no-write])))))))))

(deftest positive-legacy-evidence-requires-an-exactly-validated-session
  (fixture/with-managed-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [legacy-create
                    (fn [provider suffix]
                      (with-redefs [managed/managed-harness?
                                    (constantly false)]
                        (harnesses/create!
                         rt {:harness provider :mode :interactive
                             :cwd "/tmp/legacy-required-session"
                             :session-id (str "legacy-" suffix)
                             :title (str "legacy " suffix)})))
                    positive-outcome
                    (fn [kind invocation]
                      (merge
                       (if (= :done kind)
                         {:status :done :exit-code 0
                          :session-usable false}
                         {:status :failed :exit-code 1
                          :error "usable failure"
                          :session-usable true})
                       {:invocation invocation}))
                    snapshot
                    (fn [run]
                      (let [identity-strand
                            (identity/current rt (attr run :identity/id))]
                        {:run (weaver/show rt (:id run))
                         :identity identity-strand
                         :performed (->> (graph/outgoing-edges
                                          rt [(:id identity-strand)]
                                          "performed")
                                         (sort-by pr-str)
                                         vec)
                         :strand-count (count (weaver/list rt))}))
                    finish-results
                    (into {}
                          (for [provider [:codex :pi]
                                kind [:done :usable]]
                            (let [suffix (str (name provider) "-finish-"
                                              (name kind))
                                  run (legacy-create provider suffix)
                                  started (harnesses/begin-attempt!
                                           rt (:id run))
                                  before (snapshot run)
                                  rejected
                                  (failure #(harnesses/finish!
                                             rt (:id run)
                                             (positive-outcome
                                              kind (:invocation started))))]
                              [[provider kind]
                               {:failure rejected
                                :unchanged (= before (snapshot run))}])))
                    settle-results
                    (into {}
                          (for [provider [:codex :pi]
                                kind [:done :usable]]
                            (let [suffix (str (name provider) "-settle-"
                                              (name kind))
                                  run (legacy-create provider suffix)
                                  started (harnesses/begin-attempt!
                                           rt (:id run))
                                  terminal
                                  (harnesses/finish!
                                   rt (:id run)
                                   {:status :failed :exit-code 1
                                    :error "awaiting custody"
                                    :invocation (:invocation started)
                                    :evidence
                                    {:settled false
                                     :settlement "no-terminal-evidence"}})
                                  before (snapshot terminal)
                                  rejected
                                  (failure #(harnesses/settle-outcome!
                                             rt (:id terminal)
                                             (positive-outcome
                                              kind (:invocation started))
                                             {:settled true
                                              :settlement "process-exit"}))]
                              [[provider kind]
                               {:failure rejected
                                :unchanged (= before (snapshot terminal))}])))
                    wrong-finish
                    (let [run (legacy-create :pi "pi-wrong-finish")
                          started (harnesses/begin-attempt! rt (:id run))
                          before (snapshot run)
                          rejected
                          (failure #(harnesses/finish!
                                     rt (:id run)
                                     (assoc (positive-outcome
                                             :done (:invocation started))
                                            :session-id "unrelated-pi")))]
                      {:failure rejected
                       :unchanged (= before (snapshot run))})
                    wrong-settle
                    (let [run (legacy-create :pi "pi-wrong-settle")
                          started (harnesses/begin-attempt! rt (:id run))
                          terminal
                          (harnesses/finish!
                           rt (:id run)
                           {:status :failed :exit-code 1
                            :error "awaiting custody"
                            :invocation (:invocation started)
                            :evidence {:settled false
                                       :settlement "no-terminal-evidence"}})
                          before (snapshot terminal)
                          rejected
                          (failure #(harnesses/settle-outcome!
                                     rt (:id terminal)
                                     (assoc (positive-outcome
                                             :done (:invocation started))
                                            :session-id "unrelated-pi")
                                     {:settled true
                                      :settlement "process-exit"}))]
                      {:failure rejected
                       :unchanged (= before (snapshot terminal))})]
                {:finish finish-results
                 :settle settle-results
                 :wrong-finish wrong-finish
                 :wrong-settle wrong-settle}))]
        (doseq [path [:finish :settle]
                provider [:codex :pi]
                kind [:done :usable]]
          (testing (str (name provider) " " (name path) " " (name kind)
                        " evidence requires a supplied session")
            (is (re-find #"requires a native session id"
                         (get-in result
                                 [path [provider kind] :failure :message])))
            (is (true? (get-in result
                               [path [provider kind] :unchanged])))))
        (doseq [path [:wrong-finish :wrong-settle]]
          (testing (str (name path) " keeps Pi exact even when unusable")
            (is (re-find #"does not match its durable binding"
                         (get-in result [path :failure :message])))
            (is (true? (get-in result [path :unchanged])))))))))

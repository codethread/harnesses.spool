(ns harnesses.auto-run-test
  "Exercise repository auto-run activation in a disposable Weaver world."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [ct.spools.codethread.auto-run :as auto-run]
            [ct.spools.codethread.auto-run-worktree :as auto-run-worktree]
            [ct.spools.harnesses.assignment :as assignment]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(defn- prepare! [_runtime {:keys [repo card]}]
  (let [cwd (io/file repo (str "worktree-" (:id card)))]
    (.mkdirs cwd)
    {:cwd (.getCanonicalPath cwd)
     :branch (str "auto/" (:id card))}))

(defn- card! [runtime priority]
  (weaver/add!
   runtime
   {:title (str "Eligible fixture " priority)
    :attributes {:kanban/card "true"
                 :kanban/type "feature"
                 :kanban/lane "pending"
                 :kanban/priority priority
                 :kanban.label/auto-run "true"}}))

(defn- world-options
  ([] (world-options {}))
  ([files]
   (let [deps (:deps (edn/read-string (slurp "deps.edn")))]
     {:storage :sqlite-memory
      :deps-edn
      (pr-str
       {:deps
        (update-vals deps
                     #(if-let [root (:local/root %)]
                        (assoc % :local/root (.getCanonicalPath (io/file root)))
                        %))})
      :init-clj (slurp "init.clj")
      :files (merge
              (into {}
                    (for [path ["me/auto_run_workflows.clj"
                                "me/auto_run.clj"]]
                      [path (slurp path)]))
              files)})))

(defn- old-auto-run-source []
  "(ns harnesses.auto-run
     (:require [clojure.java.io :as io]
               [ct.spools.codethread.auto-run :as auto-run]
               [ct.spools.codethread.auto-run-worktree]
               [millstrand.api.lifecycle.alpha :as lifecycle]
               [millstrand.api.millstrand.alpha :as millstrand]))
   (millstrand/use-op! auto-run/auto-run)
   (defn open!
     \"Open old dispatcher.\"
     [{:keys [runtime]}]
     (auto-run/configure!
      runtime
      {:repo (.getCanonicalPath
              (.getParentFile
               (io/file (get-in runtime [:metadata :config-dir]))))
       :seat \"sol\" :effort \"high\" :workflow \"auto-full-land\"
       :workflows #{\"auto-full-land\"}
       :prepare 'ct.spools.codethread.auto-run-worktree/prepare!
       :enabled? true :max-running 2 :interval-ms 15000}))
   (defn close!
     \"Close old dispatcher.\"
     [{:keys [runtime]}]
     (auto-run/stop! runtime))
   (lifecycle/defresource! auto-run-dispatcher
     \"Own old dispatcher.\"
     {:open 'harnesses.auto-run/open!
      :close 'harnesses.auto-run/close!})")

(defn- old-workflow-source []
  (str/replace
   (slurp "me/auto_run_workflows.clj")
   #"(?s)\n\(workflow/defworkflow! auto-human-review.*?\(delivery false\)\)\n"
   "\n"))

(defn- auto-run-wakes [runtime]
  (filter #(= "codethread/auto-run" (:key %)) (scheduler/pending runtime)))

(defn- role-step [strands role]
  (first (filter #(= role (attr-get % :auto-run/role)) strands)))

(defn- git!
  [dir & args]
  (let [{:keys [exit out err]}
        (apply shell/sh (concat ["git" "-C" (.getPath (io/file dir))] args))]
    (when-not (zero? exit)
      (throw (ex-info "Git fixture command failed"
                      {:dir dir :args args :exit exit :out out :err err})))
    out))

(defn- quality-fixture
  [contract]
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "harnesses-quality-" (java.util.UUID/randomUUID)))
        worktree (io/file root "worktree")
        remote (io/file root "remote.git")
        branch "feature/quality-test"]
    (.mkdirs root)
    (apply git! root ["init" "--bare" (.getPath remote)])
    (apply git! root ["init" "-b" branch (.getPath worktree)])
    (git! worktree "config" "user.name" "Harnesses Test")
    (git! worktree "config" "user.email" "test@harnesses.invalid")
    (let [quality-contract (io/file worktree ".millstrand/land-quality.sh")]
      (io/make-parents quality-contract)
      (spit quality-contract contract)
      (.setExecutable quality-contract true false))
    (git! worktree "add" ".")
    (git! worktree "commit" "-m" "quality fixture")
    (git! worktree "remote" "add" "origin" (.getPath remote))
    (git! worktree "push" "-u" "origin" branch)
    {:root root
     :worktree worktree
     :branch branch
     :head (str/trim (git! worktree "rev-parse" "HEAD"))}))

(defn- delete-tree!
  [root]
  (doseq [file (reverse (file-seq root))]
    (io/delete-file file true)))

(defn- quality-result
  [argv worktree branch]
  (apply shell/sh (concat (assoc argv 4 branch) [:dir (.getPath worktree)])))

(deftest repository-activation-and-full-land-contract
  (t/with-weaver-world
    [ctx (world-options)]
    (let [rt (:runtime ctx)
          status (auto-run/status rt)]
      (testing "repository admission policy"
        (is (:enabled status))
        (is (= 2 (get-in status [:config :max-running])))
        (is (= "sol" (get-in status [:config :seat])))
        (is (= "high" (get-in status [:config :effort])))
        (is (= "auto-full-land" (get-in status [:config :workflow])))
        (is (= ["auto-full-land" "auto-human-review"]
               (get-in status [:config :workflows])))
        (is (empty? (:cards status)))
        (is (empty? (:dispatched (auto-run/scan! rt)))))
      (testing "the real policy admits exactly two eligible cards"
        (let [requests (atom [])
              cards [(card! rt "p1") (card! rt "p2") (card! rt "p3")]
              result (with-redefs [auto-run-worktree/prepare! prepare!
                                   assignment/assign!
                                   (fn [_runtime assignment-request]
                                     (swap! requests conj assignment-request)
                                     {:id (str "fixture-run-" (:target assignment-request))})]
                       (auto-run/scan! rt))
              admitted (mapv #(weaver/show rt (:card %)) (:dispatched result))]
          (is (= 2 (count admitted)))
          (is (= (mapv :id (take 2 cards)) (mapv :id admitted)))
          (is (nil? (attr-get (weaver/show rt (:id (last cards)))
                              :auto-run/status)))
          (doseq [[receipt request] (map vector admitted @requests)]
            (is (= "assigned" (attr-get receipt :auto-run/status)))
            (is (= (str "fixture-run-" (:id receipt))
                   (attr-get receipt :auto-run/run-id)))
            (is (= "sol" (:harness request)))
            (is (= "high" (get-in request [:attributes :harness/effort])))
            (is (= (:id receipt) (:target request)))
            (is (= (str "auto/" (:id receipt))
                   (attr-get receipt :auto-run/branch)))
            (current/with-runtime rt
              (let [root (workflow/current-root
                          (attr-get receipt :auto-run/workflow-run-id))
                    context (attr-get root :workflow/context)]
                (is (= (:id receipt) (:card context)))
                (is (= (str "auto/" (:id receipt)) (:branch context)))
                (is (= (attr-get receipt :auto-run/worktree)
                       (:worktree context))))))))
      (current/with-runtime rt
        (let [definition (:value (workflow/resolve-workflow :auto-full-land))
              step (fn [id]
                     (some #(when (= id (:id %)) %) (:steps definition)))
              result (workflow/start!
                      "test-auto-full-land"
                      :auto-full-land
                      {:card "fixture-card"
                       :feature "Disposable feature"
                       :branch "auto/fixture-card"
                       :worktree (:config-dir ctx)})
              root (workflow/current-root "test-auto-full-land")
              strands (:strands (graph/subgraph rt [(:id root)]))
              gates (set (keep #(attr-get % :workflow/gate) strands))
              quality-argv (attr-get
                            (some #(when (= "Pass repository quality checks"
                                            (:title %))
                                     %)
                                  strands)
                            :shell/argv)
              ci-argv (attr-get
                       (some #(when (= "Wait for the PR checks" (:title %)) %) strands)
                       :shell/argv)
              handoff (workflow/step-view (role-step strands "handoff-worker"))
              finisher (workflow/step-view (role-step strands "finisher"))]
          (is (= ["Implement and verify the assigned feature"]
                 (mapv :title (:ready result))))
          (is (contains? gates "shell"))
          (is (contains? gates "code"))
          (is (not (contains? gates "agent"))
              "The finisher is a deliberate handoff, not an eager agent gate")
          (testing "publication precedes quality"
            (let [publish (step :publish)
                  quality (step :quality)
                  instruction (get-in publish [:attributes "workflow/instruction"])]
              (is (= [:implement] (:depends-on publish)))
              (is (= [:publish] (:depends-on quality)))
              (is (str/includes? (instruction {:branch "auto/fixture-card"})
                                 "git push --set-upstream origin auto/fixture-card"))
              (is (= "sh" (first quality-argv)))
              (is (= "auto-run-quality" (nth quality-argv 3)))
              (is (= "auto/fixture-card" (nth quality-argv 4)))
              (is (= ["sh" "-c"] (subvec ci-argv 0 2)))
              (is (= ["pr-checks" "allow-empty" "auto/fixture-card" "120" "5"]
                     (subvec ci-argv (- (count ci-argv) 5))))
              (is (= [:ci] (:depends-on (step :review-card))))
              (testing "the shared gate accepts a clean published revision"
                (let [fixture (quality-fixture "#!/bin/sh\nexit 0\n")]
                  (try
                    (is (zero? (:exit (quality-result quality-argv
                                                       (:worktree fixture)
                                                       (:branch fixture)))))
                    (finally
                      (delete-tree! (:root fixture))))))
              (testing "the shared gate rejects an unbound or changed revision"
                (doseq [[label contract mutate!]
                        [["wrong branch" "#!/bin/sh\nexit 0\n"
                          #(git! (:worktree %) "checkout" "-b" "feature/other")]
                         ["dirty worktree" "#!/bin/sh\nexit 0\n"
                          #(spit (io/file (:worktree %) "dirty") "dirty\n")]
                         ["unpushed HEAD" "#!/bin/sh\nexit 0\n"
                          #(do (spit (io/file (:worktree %) "unpushed") "unpushed\n")
                               (git! (:worktree %) "add" ".")
                               (git! (:worktree %) "commit" "-m" "unpushed"))]
                         ["remote target advanced behind a stale tracking ref"
                          "#!/bin/sh\nexit 0\n"
                          #(let [{:keys [worktree branch head]} %]
                             (git! worktree "commit" "--allow-empty" "-m" "remote revision")
                             (git! worktree "push" "origin"
                                   (str "HEAD:refs/heads/" branch))
                             (git! worktree "reset" "--hard" head)
                             (git! worktree "update-ref"
                                   (str "refs/remotes/origin/" branch) head))]
                         ["remote target advances during quality checks"
                          (str "#!/bin/sh\nset -eu\n"
                               "git commit --allow-empty -m remote-revision\n"
                               "git push origin \"HEAD:refs/heads/$LAND_EXPECTED_BRANCH\"\n"
                               "git reset --hard \"$LAND_EXPECTED_HEAD\"\n"
                               "git update-ref \"refs/remotes/origin/$LAND_EXPECTED_BRANCH\" "
                               "\"$LAND_EXPECTED_HEAD\"\n")
                          identity]
                         ["changed tested HEAD"
                          "#!/bin/sh\nset -eu\ngit commit --allow-empty -m changed\n"
                          identity]]]
                  (let [fixture (quality-fixture contract)]
                    (try
                      (mutate! fixture)
                      (let [{:keys [exit]} (quality-result quality-argv
                                                           (:worktree fixture)
                                                           (:branch fixture))]
                        (is (not (zero? exit)) label))
                      (finally
                        (delete-tree! (:root fixture)))))))))
          (testing "worker and finisher have separate targets and authority"
            (is (= "step" (:role handoff) (:role finisher)))
            (is (not= (:id handoff) (:id finisher)))
            (is (str/includes? (:instruction handoff)
                               "STOP at land's signoff checkpoint BEFORE choosing approved"))
            (is (str/includes? (:instruction handoff)
                               "auto-run/finisher-run-id"))
            (is (str/includes? (:instruction finisher)
                               "This step is finisher-only"))
            (is (str/includes? (:instruction finisher)
                               "Verify land is done and the card is closed with outcome done"))
            (is (not (str/includes? (:instruction finisher)
                                    "agent run grunt")))))))))

(deftest source-refresh-reconciles-running-dispatcher
  (t/with-weaver-world
    [ctx (world-options {"me/auto_run.clj" (old-auto-run-source)
                         "me/auto_run_workflows.clj" (old-workflow-source)})]
    (let [rt (:runtime ctx)
          card (card! rt "p1")
          dispatch (with-redefs [auto-run-worktree/prepare! prepare!
                                 assignment/assign!
                                 (fn [_runtime request]
                                   {:id (str "fixture-run-" (:target request))})]
                     (auto-run/scan! rt))
          accepted (weaver/show rt (:id card))]
      (is (= ["auto-full-land"] (get-in (auto-run/status rt)
                                         [:config :workflows])))
      (is (= 1 (count (:dispatched dispatch))))
      (is (= "assigned" (attr-get accepted :auto-run/status)))
      (spit (io/file (:config-dir ctx) "me/auto_run.clj")
            (slurp "me/auto_run.clj"))
      (spit (io/file (:config-dir ctx) "me/auto_run_workflows.clj")
            (slurp "me/auto_run_workflows.clj"))
      (let [refresh (runtime/refresh! rt)
            wake (vec (auto-run-wakes rt))]
        (is (empty? (:residuals refresh)))
        (is (= ["auto-full-land" "auto-human-review"]
               (get-in (auto-run/status rt) [:config :workflows])))
        (is (= "assigned"
               (attr-get (weaver/show rt (:id card)) :auto-run/status)))
        (is (= 1 (count wake)))
        (current/with-runtime rt
          (is (= #{:start}
                 (:entrypoints (workflow/resolve-workflow
                                :auto-human-review)))))
        (is (empty? (:residuals (runtime/refresh! rt))))
        (is (= wake (vec (auto-run-wakes rt))))
        (is (= "assigned"
               (attr-get (weaver/show rt (:id card))
                         :auto-run/status)))))))

(deftest human-review-contract-stops-before-landing
  (t/with-weaver-world
    [ctx (world-options)]
    (let [rt (:runtime ctx)]
      (current/with-runtime rt
        (let [definition (:value (workflow/resolve-workflow :auto-human-review))
              result (workflow/start!
                      "test-auto-human-review"
                      :auto-human-review
                      {:card "fixture-card"
                       :feature "Disposable feature"
                       :branch "auto/fixture-card"
                       :worktree (:config-dir ctx)})
              root (workflow/current-root "test-auto-human-review")
              strands (:strands (graph/subgraph rt [(:id root)]))
              views (map workflow/step-view strands)
              checkpoint (first (filter #(= "human" (:checkpoint-kind %)) views))]
          (is (= ["Implement and verify the assigned feature"]
                 (mapv :title (:ready result))))
          (is (= [:review-card]
                 (:depends-on (some #(when (= :human-acceptance (:id %)) %)
                                    (:steps definition)))))
          (is (= ["reviewed"] (:choices checkpoint)))
          (is (str/includes? (:instruction checkpoint)
                             "Do not choose this checkpoint"))
          (is (str/includes? (:instruction checkpoint) "exact head SHA"))
          (is (not-any? #(str/includes? (or (:instruction %) "")
                                        "auto-land-finisher/")
                        views))
          (is (nil? (role-step strands "handoff-worker")))
          (is (nil? (role-step strands "finisher"))))))))

(defn -main
  "Run the disposable workspace activation test."
  [& _]
  (let [{:keys [fail error]} (run-tests 'harnesses.auto-run-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))

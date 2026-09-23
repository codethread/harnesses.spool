(ns harnesses.auto-run-test
  "Exercise repository auto-run activation in a disposable Weaver world."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [millhouse.spools.auto-run :as auto-run]
            [millhouse.spools.auto-run-worktree :as auto-run-worktree]
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
               [millhouse.spools.auto-run :as auto-run]
               [millhouse.spools.auto-run-worktree]
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
       :prepare 'millhouse.spools.auto-run-worktree/prepare!
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
    (spit (io/file worktree ".millstrand/published-candidate.sh")
          (slurp "published-candidate.sh"))
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

(defn- quality-marker [worktree]
  (io/file worktree (str/trim (git! worktree "rev-parse" "--git-path"
                                    "millstrand-land-quality-head"))))

(defn- quality-result
  [argv worktree branch]
  (apply shell/sh (concat (assoc argv 2 branch) [:dir (.getPath worktree)])))

(deftest repository-activation-and-full-land-contract
  (t/with-weaver-world
    [ctx (world-options)]
    (let [rt (:runtime ctx)
          status (auto-run/status rt)]
      (testing "repository admission policy"
        (is (= {:enabled true
                :max-running 2
                :seat "sol"
                :effort "high"
                :workflow "auto-full-land"
                :workflows ["auto-full-land" "auto-human-review"]}
               (select-keys (assoc (:config status) :enabled (:enabled status))
                            [:enabled :max-running :seat :effort :workflow :workflows])))
        (is (empty? (:dispatched (auto-run/scan! rt))))
        (let [card (weaver/add! rt {:title "Blocked work"})
              evidence (weaver/add! rt {:title "Decision context"})]
          (weaver/op! rt 'weave
                      ["--pattern" "auto-run-needs-decision" "--input"
                       (json/write-str {:strand (:id card)
                                        :evidence (:id evidence)})])
          (let [reported (weaver/show rt (:id card))]
            (is (= "needs-decision"
                   (attr-get reported :auto-run/agent-blocked-status)))
            (is (= (:id evidence)
                   (attr-get reported :auto-run/agent-evidence)))
            (is (= "true" (attr-get reported :kanban.label/agent-blocked)))
            (is (= "true" (attr-get reported :kanban.label/needs-decision))
                "The repository activates the reporting patterns and label hook"))))
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
              handoff (role-step strands "handoff-worker")
              finisher (role-step strands "finisher")]
          (is (= ["Implement and verify the assigned feature"]
                 (mapv :title (:ready result))))
          (is (contains? gates "shell"))
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
              (is (= ["sh" ".millstrand/published-candidate.sh" "auto/fixture-card"]
                     quality-argv))
              (is (= ["sh" "-c"] (subvec ci-argv 0 2)))
              (is (= ["pr-checks" "allow-empty" "auto/fixture-card" "120" "5"]
                     (subvec ci-argv (- (count ci-argv) 5))))
              (is (nil? (step :review-card)))
              (is (= [:ci] (:depends-on (step :land))))
              (testing "the repository candidate gate accepts a clean published revision"
                (let [fixture (quality-fixture "#!/bin/sh\nexit 0\n")]
                  (try
                    (is (zero? (:exit (quality-result quality-argv
                                                      (:worktree fixture)
                                                      (:branch fixture)))))
                    (is (= (:head fixture)
                           (str/trim (slurp (quality-marker (:worktree fixture))))))
                    (finally
                      (delete-tree! (:root fixture))))))
              (testing "the repository candidate gate rejects an unbound or changed revision"
                (doseq [[label contract mutate!]
                        [["failed quality" "#!/bin/sh\nexit 7\n" identity]
                         ["dirty after quality" "#!/bin/sh\ntouch dirty\n" identity]
                         ["remote branch deleted during quality"
                          "#!/bin/sh\ngit push origin --delete \"$LAND_EXPECTED_BRANCH\"\n"
                          identity]
                         ["wrong branch" "#!/bin/sh\nexit 0\n"
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
                      (spit (quality-marker (:worktree fixture)) (:head fixture))
                      (mutate! fixture)
                      (let [{:keys [exit]} (quality-result quality-argv
                                                           (:worktree fixture)
                                                           (:branch fixture))]
                        (is (not (zero? exit)) label)
                        (is (not (.exists (quality-marker (:worktree fixture)))) label))
                      (finally
                        (delete-tree! (:root fixture)))))))))
          (testing "repository policy delegates landing to separate shared roles"
            (is (some? handoff))
            (is (some? finisher))
            (is (not= (:id handoff) (:id finisher)))))))))

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
          (is (= 1 (count (:ready result))))
          (is (= [:review-card]
                 (:depends-on (some #(when (= :human-acceptance (:id %)) %)
                                    (:steps definition)))))
          (is (= ["reviewed"] (:choices checkpoint)))
          (is (nil? (role-step strands "handoff-worker")))
          (is (nil? (role-step strands "finisher"))))))))

(deftest delivery-ready-frontier-preserves-attention-boundary
  (t/with-weaver-world
    [ctx (world-options)]
    (let [rt (:runtime ctx)]
      (current/with-runtime rt
        (doseq [route [:auto-full-land :auto-human-review]]
          (let [card (card! rt "p2")
                run-id (str "frontier-" (name route))]
            (weaver/update! rt (:id card) {:attributes {:kanban/lane "claimed"}})
            (workflow/start! run-id route
                             {:card (:id card) :feature "Fixture"
                              :branch "auto/fixture" :worktree (:config-dir ctx)})
            ;; Supply fixture executor evidence, without running GitHub or quality.
            (doseq [title ["Implement and verify the assigned feature"
                           "Publish the committed branch before quality checks"
                           "Pass repository quality checks"
                           "Publish the exact change with its review package"
                           "Wait for the PR checks"]]
              (let [frontier (workflow/ready run-id)]
                (is (= [title] (mapv :title frontier)))
                (workflow/run-complete!
                 {:run-id run-id :step (:id (first frontier))
                  :executor "fixture"})))
            (if (= route :auto-full-land)
              (do
                (is (= "claimed" (attr-get (weaver/show rt (:id card)) :kanban/lane)))
                (is (= "handoff-worker"
                       (attr-get (weaver/show rt (:id (first (workflow/ready run-id))))
                                 :auto-run/role))))
              (do
                ;; The real code executor owns the attention transition.
                (workflow/await! run-id {:timeout-secs 10 :poll-ms 10})
                (is (= "in_review"
                       (attr-get (weaver/show rt (:id card)) :kanban/lane)))
                (is (= ["human"] (mapv :checkpoint-kind (workflow/ready run-id))))))))))))

(defn -main
  "Run the disposable workspace activation test."
  [& _]
  (let [{:keys [fail error]} (run-tests 'harnesses.auto-run-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))

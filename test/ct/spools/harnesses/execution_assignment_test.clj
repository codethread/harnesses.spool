(ns ct.spools.harnesses.execution-assignment-test
  "External Weaver acceptance for assignment scheduling and process custody."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private millstrand-sha
  "The Millstrand revision required by the external acceptance world."
  "310368dff9174bd889ad21d4ed8196952684eaf9")

(def ^:private fixture-module
  "(ns me.execution-assignment-fixture
     (:require [ct.spools.harnesses :as harnesses]
               [ct.spools.harnesses.agent-bin :as agent-bin]
               [ct.spools.harnesses.agent-cli :as agent-cli]
               [ct.spools.harnesses.assignment :as assignment]
               [ct.spools.harnesses.execution :as execution]
               [ct.spools.harnesses.process-custody :as process-custody]
               [ct.spools.harnesses.providers.claude :as claude]
               [ct.spools.harnesses.providers.codex :as codex]
               [ct.spools.harnesses.providers.cursor :as cursor]
               [ct.spools.harnesses.providers.pi :as pi]
               [millstrand.api.lifecycle.alpha :as lifecycle]
               [millstrand.api.millstrand.alpha :as millstrand]
               [millstrand.api.spool.alpha :refer [attr-get]]))

   (defn prepare
     [_rt _definition run]
     (let [root (attr-get run :harness.fixture/root)
           name (attr-get run :harness.fixture/name)]
       {:argv
        [\"/bin/sh\" \"-eu\" \"-c\"
         (str \"root=$1; name=$2; \"
              \"printf '%s\\n%s\\n%s\\n' \\\"$MILLSTRAND_AGENT_ID\\\" \\\"$MILLSTRAND_RUN_ID\\\" \\\"$MILLSTRAND_WORKSPACE\\\" > \\\"$root/$name.env\\\"; \"
              \"cat > \\\"$root/$name.guidance\\\"; \"
              \"touch \\\"$root/$name.started\\\"; \"
              \"while [ ! -f \\\"$root/$name.release\\\" ]; do sleep 0.02; done; \"
              \"printf released; touch \\\"$root/$name.finished\\\"\")
         \"fixture-provider\" root name]
        :env {\"MILLSTRAND_AGENT_ID\" \"hostile-agent\"
              \"MILLSTRAND_RUN_ID\" \"hostile-run\"
              \"MILLSTRAND_WORKSPACE\" \"/hostile/workspace\"}
        :stdin (str (attr-get run :harness/prompt) \"\\n\")}))

   (defn finish
     [_rt _definition _run {:keys [exit-code stdout stderr]}]
     (if (zero? exit-code)
       {:status :done
        :exit-code exit-code
        :result stdout
        :session-usable true}
       {:status :failed
        :exit-code exit-code
        :error (or stderr \"fixture provider failed\")
        :session-usable false}))

   (defn open-fixture!
     [{:keys [runtime]}]
     (harnesses/register-harness!
      runtime :fixture
      {:modes #{:headless}
       :prepare 'me.execution-assignment-fixture/prepare
       :finish 'me.execution-assignment-fixture/finish})
     (assignment/register-assign-policy!
      runtime {:kind :assign-policy
               :name :fixture-policy
               :text \"Fixture policy: preserve this opaque guidance exactly.\"})
     {:opened :fixture})

   (defn close-fixture!
     [{:keys [runtime]}]
     (harnesses/unregister-harness! runtime :fixture)
     {:closed :fixture})

   (lifecycle/defresource fixture-runtime
     \"Register the deterministic local subprocess fixture.\"
     {:open 'me.execution-assignment-fixture/open-fixture!
      :close 'me.execution-assignment-fixture/close-fixture!
      :after #{:assignment-runtime}})

   (millstrand/use-op! agent-cli/agent)
   (millstrand/use-handler! execution/on-event)
   (millstrand/use-bin! agent-bin/agent)

   (lifecycle/use-resource!
    harnesses/harness-core-runtime
    assignment/assignment-runtime
    claude/claude-harness-runtime
    codex/codex-harness-runtime
    cursor/cursor-harness-runtime
    pi/pi-harness-runtime
    fixture-runtime
    execution/harness-execution-runtime)

   (lifecycle/use-reconcile! process-custody/harness-process-custody)")

(defn- required-executable [name]
  (let [path (System/getenv name)
        file (some-> path io/file)]
    (when-not (and file (.isFile file) (.canExecute file))
      (throw (ex-info (str name " must name an executable")
                      {:environment-variable name :value path})))
    (.getCanonicalPath file)))

(defn- command-result [argv]
  (let [process (.start (doto (ProcessBuilder. (mapv str argv))
                          (.redirectErrorStream true)))
        output (slurp (.getInputStream process))]
    {:argv argv :exit (.waitFor process) :output output}))

(defn- command! [argv]
  (let [result (command-result argv)]
    (when-not (zero? (:exit result))
      (throw (ex-info "External assignment acceptance command failed" result)))
    (json/read-str (:output result) :key-fn keyword)))

(defn- mill! [mill workspace & args]
  (command! (into [mill] (concat args ["--workspace" workspace "--json"]))))

(defn- strand! [strand workspace & args]
  (command! (into [strand "--workspace" workspace] args)))

(defn- create-temp-dir []
  (.toFile (Files/createTempDirectory
            (.toPath (io/file "/tmp"))
            "harness-assignment-e2e-"
            (make-array FileAttribute 0))))

(defn- delete-tree! [root]
  (when (.exists root)
    (with-open [paths (Files/walk
                       (.toPath root)
                       (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (sort-by #(.getNameCount %) >
                            (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists path)))))

(defn- write-world! [root project-root]
  (let [workspace (io/file root "w")]
    (.mkdirs (io/file workspace "me"))
    (spit (io/file workspace "config.json")
          "{\"configFormat\":\"alpha\",\"autoStart\":false}")
    (spit (io/file workspace "deps.edn")
          (pr-str
           {:deps
            {'millstrand.spools/batteries
             {:git/url "https://github.com/codethread/millstrand.git"
              :git/sha millstrand-sha
              :deps/root "spools/batteries"}
             'millhouse.spools/identity
             {:git/url "https://github.com/codethread/millhouse.spool.git"
              :git/sha "7cfcb235848c0db6231ba2282430913580d66f2e"
              :deps/root "spools/identity"}
             'ct.spools/harnesses
             {:local/root project-root}}}))
    (spit (io/file workspace "init.clj")
          "(require '[millstrand.api.current.alpha :as current]
                    '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :batteries
             {:ns 'millstrand.spools.batteries :required? true})
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity :required? true})
           (runtime/module! rt :fixture
             {:file \"me/execution_assignment_fixture.clj\"
              :after [:identity] :required? true})")
    (spit (io/file workspace "me/execution_assignment_fixture.clj") fixture-module)
    (.getCanonicalPath workspace)))

(defn- await-file! [file]
  (loop [remaining 300]
    (cond
      (.isFile file) file
      (zero? remaining)
      (throw (ex-info "Timed out awaiting subprocess evidence"
                      {:file (.getCanonicalPath file)}))
      :else
      (do (Thread/sleep 50)
          (recur (dec remaining))))))

(defn- await-run! [strand workspace run-id predicate]
  (loop [remaining 300]
    (let [run (strand! strand workspace "agent" "show" run-id)]
      (cond
        (predicate run) run
        (zero? remaining)
        (throw (ex-info "Timed out awaiting agent run evidence"
                        {:run-id run-id :run run}))
        :else
        (do (Thread/sleep 50)
            (recur (dec remaining)))))))

(defn- add-card! [strand workspace title & edge]
  (:id (apply strand! strand workspace "add" title
              "--attributes"
              "{\"kanban/card\":\"true\",\"kanban/type\":\"feature\",\"kanban/lane\":\"pending\"}"
              edge)))

(defn- assign! [strand workspace markers target name]
  (strand! strand workspace "agent" "assign" "fixture"
           "--task" target
           "--cwd" (.getCanonicalPath markers)
           "--policy" "fixture-policy"
           "--request-id" (str "fixture-" target)
           "--attributes"
           (json/write-str {"harness.fixture/root" (.getCanonicalPath markers)
                            "harness.fixture/name" name})))

(defn run-acceptance!
  "Run the assignment fixture through an external, Mill-admitted Weaver."
  []
  (let [mill (required-executable "MILLSTRAND_MILL_BIN")
        strand (required-executable "MILLSTRAND_STRAND_BIN")
        project-root (.getCanonicalPath (io/file (System/getProperty "user.dir")))
        root (create-temp-dir)
        markers (io/file root "markers")
        workspace (write-world! root project-root)]
    (.mkdirs markers)
    (try
      (mill! mill workspace "weaver" "start")
      (try
        (let [predecessor-target (add-card! strand workspace "Fixture predecessor")
              blocked-target (add-card! strand workspace "Fixture blocked"
                                        "--edge" (str "depends-on:" predecessor-target))
              parallel-a-target (add-card! strand workspace "Fixture parallel A")
              parallel-b-target (add-card! strand workspace "Fixture parallel B")
              predecessor (assign! strand workspace markers predecessor-target "predecessor")
              blocked (assign! strand workspace markers blocked-target "blocked")]
          (await-file! (io/file markers "predecessor.started"))
          (testing "a blocked accepted assignment has no process or owner"
            (is (not (.exists (io/file markers "blocked.started"))))
            (is (nil? (get-in (strand! strand workspace "show" blocked-target)
                              [:attributes :owner]))))
          (spit (io/file markers "predecessor.release") "released")
          (await-file! (io/file markers "predecessor.finished"))
          (let [settled (await-run! strand workspace (:id predecessor) :settled)]
            (testing "process completion alone does not release dependent work"
              (is (= ["stopped" "completed" true]
                     ((juxt :status :substatus :settled) settled))
                  (pr-str settled))
              (is (not (.exists (io/file markers "blocked.started"))))
              (is (= "active" (:state (strand! strand workspace "show"
                                               predecessor-target))))))
          (strand! strand workspace "update" predecessor-target "--state" "closed")
          (await-file! (io/file markers "blocked.started"))
          (let [parallel-a (assign! strand workspace markers parallel-a-target "parallel-a")
                parallel-b (assign! strand workspace markers parallel-b-target "parallel-b")]
            (await-file! (io/file markers "parallel-a.started"))
            (await-file! (io/file markers "parallel-b.started"))
            (testing "independent assignments overlap as running processes"
              (let [runs (mapv #(strand! strand workspace "agent" "show" (:id %))
                               [parallel-a parallel-b])]
                (is (= ["running" "running"] (mapv :status runs))
                    (pr-str runs)))
              (is (not (.exists (io/file markers "parallel-a.finished"))))
              (is (not (.exists (io/file markers "parallel-b.finished"))))))
          (testing "the child receives enriched guidance and authoritative env"
            (let [[agent-id run-id child-workspace]
                  (str/split-lines (slurp (io/file markers "blocked.env")))
                  guidance (slurp (io/file markers "blocked.guidance"))]
              (is (= (:id blocked) run-id))
              (is (not= "hostile-agent" agent-id))
              (is (not= "hostile-run" run-id))
              (is (= workspace child-workspace))
              (is (str/includes? guidance (:id blocked)))
              (is (str/includes? guidance agent-id))
              (is (str/includes?
                   guidance
                   "Fixture policy: preserve this opaque guidance exactly."))))
          (doseq [name ["blocked" "parallel-a" "parallel-b"]]
            (spit (io/file markers (str name ".release")) "released")
            (await-file! (io/file markers (str name ".finished"))))
          (testing "custody settles runs without closing their targets"
            (doseq [[run target]
                    [[blocked blocked-target]
                     [(strand! strand workspace "agent" "show"
                               "--task" parallel-a-target) parallel-a-target]
                     [(strand! strand workspace "agent" "show"
                               "--task" parallel-b-target) parallel-b-target]]]
              (let [settled (await-run! strand workspace (:id run) :settled)]
                (is (= ["stopped" "completed" true]
                       ((juxt :status :substatus :settled) settled))
                    (pr-str settled))
                (is (= "active" (:state (strand! strand workspace "show" target)))))))
          {:millstrand-sha millstrand-sha
           :workspace workspace
           :runs 4
           :assertions :passed})
        (finally
          (mill! mill workspace "weaver" "stop")))
      (finally
        (delete-tree! root)))))

(deftest external-weaver-obeys-assignment-readiness-and-custody
  (is (= {:millstrand-sha millstrand-sha
          :runs 4
          :assertions :passed}
         (dissoc (run-acceptance!) :workspace))))

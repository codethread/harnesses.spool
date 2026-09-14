(ns ct.spools.harnesses.guidance-test
  "Focused native managed-guidance protocol and lifecycle tests."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.providers.claude :as claude]
            [ct.spools.harnesses.providers.codex :as codex]
            [ct.spools.harnesses.providers.cursor :as cursor]
            [ct.spools.harnesses.providers.pi :as pi]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Instant]))

(def ^:private runtime {})

(defn- provider-run [harness transport]
  {:id "run"
   :title (str harness " run")
   :state "active"
   :attributes
   (merge
    {:harness/mode "headless"
     :harness/session-id "native-session"
     :harness/prompt "Main task"
     :identity/prompt "Identity first."
     :harness/appended-system-prompts ["same" "same"]
     :harness/model "model"
     :harness/effort "high"
     :harness/extra-argv ["--unrelated" "value"]}
    (when (= "native-v1" transport)
      {:harness/guidance-version 1
       :harness/guidance-transport transport
       :harness/guidance-capability {}
       :harness/guidance-capability-sha256 "capability"
       :harness/guidance-context-template {}
       :harness/guidance-context {}
       :harness/guidance-bundle-sha256 "bundle"
       :harness/guidance-attempts []}))})

(deftest guidance-deadlines-distinguish-pi-idle-after-fetch
  (let [record {"attempt" 1 "invocation" "invocation"
                "state" "pending" "deadline-at" "2026-09-14T00:00:00Z"}
        run {:id "run" :attributes
             (merge (:attributes (provider-run "codex" "native-v1"))
                    {:harness/harness "codex"
                     :harness/attempt 1
                     :harness/invocation "invocation"
                     :harness/guidance-attempts [record]})}
        after (Instant/parse "2026-09-14T00:00:01Z")]
    (is (true? (guidance/deadline-expired? run record after)))
    (is (false? (guidance/deadline-expired?
                 (-> run
                     (assoc-in [:attributes :harness/harness] "pi")
                     (assoc-in [:attributes :harness/mode] "interactive"))
                 (assoc record "state" "fetched") after)))
    (is (true? (guidance/deadline-expired?
                (-> run
                    (assoc-in [:attributes :harness/harness] "pi")
                    (assoc-in [:attributes :harness/mode] "headless"))
                (assoc record "state" "fetched") after)))))

(deftest production-admission-is-disabled-and-hostile-argv-fails-first
  (is (empty? (capability/production-allowlist)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"no accepted production capability"
       (guidance/select!
        {:metadata {:config-dir "/tmp"}}
        {:harness "codex" :requested "native-v1" :mode :headless
         :cwd "/tmp" :env {} :effective {:harness/extra-argv []}})))
  (doseq [[harness argv] [["codex" ["--config" "'developer_instructions'=\"x\""]]
                          ["codex" ["-cdeveloper_instructions=\"x\""]]
                          ["pi" ["--append-system-prompt=x"]]
                          ["pi" ["--system-prompt" "x"]]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"wrapper-level --append-system-prompt"
         (guidance/select!
          {:metadata {:config-dir "/tmp"}}
          {:harness harness :requested "native-v1" :mode :headless
           :cwd "/tmp" :env {}
           :effective {:harness/extra-argv argv}})))))

(deftest provider-argv-removes-only-harnesses-guidance-in-native-mode
  (let [legacy-codex (codex/prepare runtime (codex/harness runtime)
                                    (provider-run "codex" "legacy"))
        native-codex (codex/prepare runtime (codex/harness runtime)
                                    (provider-run "codex" "native-v1"))
        legacy-pi (pi/prepare runtime (pi/harness runtime)
                              (provider-run "pi" "legacy"))
        native-pi (pi/prepare runtime (pi/harness runtime)
                              (provider-run "pi" "native-v1"))]
    (is (some #(str/starts-with? % "developer_instructions=")
              (:argv legacy-codex)))
    (is (not-any? #(str/starts-with? % "developer_instructions=")
                  (:argv native-codex)))
    (is (= 3 (count (filter #{"--append-system-prompt"}
                            (:argv legacy-pi)))))
    (is (zero? (count (filter #{"--append-system-prompt"}
                              (:argv native-pi)))))
    (doseq [launch [native-codex native-pi]]
      (is (= "Main task\n" (:stdin launch)))
      (is (some #{"model"} (:argv launch)))
      (is (some #{"--unrelated"} (:argv launch)))))
  (testing "maintenance providers retain their exact launch preparation"
    (let [run (provider-run "maintenance" "legacy")]
      (is (= (claude/prepare runtime (claude/harness runtime) run)
             (claude/prepare runtime (claude/harness runtime) run)))
      (is (= (cursor/prepare runtime (cursor/harness runtime) run)
             (cursor/prepare runtime (cursor/harness runtime) run))))))

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    {:deps
     {'ct.spools/harnesses {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity {:local/root (.getCanonicalPath identity-root)}}}))

(defn with-guidance-world
  "Run `f` in a disposable in-memory Weaver world with Harnesses dependencies."
  [f]
  (test-alpha/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn (pr-str (world-deps))
          :init-clj
          "(require '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.runtime.alpha :as runtime])
           (def rt (current/runtime))
           (runtime/module! rt :identity
             {:ns 'millhouse.spools.identity :required? true})
           (runtime/module! rt :guidance-core
             {:file \"modules/guidance_core.clj\"
              :after [:identity] :required? true})"
          :files
          {"modules/guidance_core.clj"
           "(ns modules.guidance-core
              (:require [ct.spools.harnesses :as harnesses]
                        [millstrand.api.lifecycle.alpha :as lifecycle]))
            (lifecycle/use-resource! harnesses/harness-core-runtime)"}}]
    (f ctx)))

(def lifecycle-setup
  "Remote disposable-world setup shared by guidance lifecycle tests."
  '(do
     (require '[clojure.data.json :as json]
              '[clojure.string :as str]
              '[ct.spools.harnesses :as harnesses]
              '[ct.spools.harnesses.execution :as execution]
              '[ct.spools.harnesses.internal.guidance :as guidance]
              '[ct.spools.harnesses.internal.guidance-capability :as capability]
              '[ct.spools.harnesses.internal.guidance-closure :as closure]
              '[ct.spools.harnesses.internal.strict-json :as strict-json]
              '[ct.spools.harnesses.providers.codex :as codex]
              '[millstrand.api.current.alpha :as current]
              '[millstrand.api.spool.alpha :as spool]
              '[millstrand.api.weaver.alpha :as weaver])
     (def rt (current/runtime))
     (harnesses/register-harness! rt :codex (codex/harness rt))
     (def fixture-dir (.toFile
                       (java.nio.file.Files/createTempDirectory
                        "guidance-profile" (make-array java.nio.file.attribute.FileAttribute 0))))
     (def provider-link (java.io.File. fixture-dir "codex"))
     (java.nio.file.Files/createSymbolicLink
      (.toPath provider-link) (.toPath (java.io.File. "/usr/bin/true"))
      (make-array java.nio.file.attribute.FileAttribute 0))
     (def scripts-dir (doto (java.io.File. fixture-dir "scripts") .mkdirs))
     (def preflight-file
       (java.io.File. scripts-dir "managed-guidance-preflight.mjs"))
     (spit preflight-file "// accepted test-only preflight\n")
     (harnesses/register-alias!
      rt :native-codex
      {:doc "Exact disposable native profile."
       :parent :codex
       :env {"PATH" (.getCanonicalPath fixture-dir)}
       :attributes {}})
     (def capability-document
       {"schema" "millstrand.agent-guidance-capability/v1"
        "harness" "codex"
        "adapter-contract" "native-v1"
        "adapter-sha256" (apply str (repeat 64 "a"))
        "executable-sha256" (capability/file-sha256 "/usr/bin/true")
        "host-version" "0.154.0-test"
        "launch-profile-sha256" (apply str (repeat 64 "b"))
        "max-context-bytes" 3072
        "hook-fact"
        {"eventName" "sessionStart"
         "key" "managed-guidance"
         "source" "plugin"
         "sourcePath" "/test/plugin/hooks.json"
         "pluginId" "agents-test"
         "command" "node managed-guidance.js"
         "enabled" true
         "trustStatus" "trusted"
         "currentHash" "trusted-host-hash"
         "timeoutSec" 15
         "additionalContextLimit" 4096}})
     (def profile-environment
       (-> (into {} (System/getenv))
           (assoc "PATH" (.getCanonicalPath fixture-dir))
           (dissoc "NODE_OPTIONS" "NODE_PATH" "OPENSSL_CONF")))
     (def profile
       (let [candidate
             {:harness "codex"
              :preflight {:path (.getCanonicalPath preflight-file)
                          :sha256 (capability/file-sha256 preflight-file)}
              :capability capability-document
              :executable-closure
              {:schema "millstrand.local-guidance-executable-closure/v1"
               :reviewed-complete true
               :resolver-policy (closure/resolver-policy)
               :artifacts
               [(closure/artifact "entrypoint" preflight-file)
                (closure/artifact
                 "interpreter"
                 (capability/resolve-executable "node" (System/getenv)))
                (closure/artifact "ownership-scanner" "/bin/ps")]
               :resolution-inputs
               {:cwd (.getCanonicalPath fixture-dir)
                :environment
                (closure/resolution-environment profile-environment)}}
              :process-ownership
              {:contract "private-posix-session/inherited-process-group-v1"
               :reviewed-closure-sha256 (apply str (repeat 64 "0"))
               :child-process-behavior "inherited-process-group-only"}}]
         (assoc-in candidate
                   [:process-ownership :reviewed-closure-sha256]
                   (capability/process-ownership-sha256 candidate))))
     (defn accepted-runner [accepted-profile request-json]
       (strict-json/parse-object! request-json 65536 "test preflight request")
       {:source (:preflight accepted-profile)
        :reviewed-closure-sha256
        (get-in accepted-profile
                [:process-ownership :reviewed-closure-sha256])
        :exit-code 0
        :stdout (strict-json/canonical-json
                 {"schema" "millstrand.agent-guidance-preflight/v1"
                  "result" "capable"
                  "capability" capability-document})
        :stderr ""})
     (defn attr [run key] (spool/attr-get run key))
     (defn guidance-receipt [bundle outcome]
       (cond-> {"schema" "millstrand.agent-guidance-receipt/v1"
                "run-id" (:run-id bundle)
                "attempt" (:attempt bundle)
                "invocation" (:invocation bundle)
                "harness" (:harness bundle)
                "native-session-id" (:native-session-id bundle)
                "transport" "native-v1"
                "bundle-sha256" (:bundle-sha256 bundle)
                "capability-sha256" (:capability-sha256 bundle)
                "outcome" outcome}
         (= "failed" outcome)
         (assoc "stage" "rendering" "code" "fixture-failure"
                "diagnostic" "adapter could not render the bundle")))))

(deftest managed-default-is-durable-byte-compatible-legacy
  (with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do lifecycle-setup
              '(let [run (harnesses/create!
                          rt {:harness :codex :mode :interactive :cwd "/tmp"
                              :prompt "Do the work"})
                     started (harnesses/begin-attempt! rt (:id run))
                     prepared (codex/prepare rt (codex/harness rt)
                                             (:strand started))
                     process-spec (#'execution/process-spec
                                   rt (:strand started) prepared)
                     count-before
                     (count (weaver/list rt
                                         [:= [:attr "harness/run"] "true"]
                                         {}))
                     native-error
                     (try
                       (harnesses/create!
                        rt {:harness :native-codex :mode :headless
                            :cwd "/tmp" :prompt "Native admission probe"
                            :guidance-transport "native-v1"})
                       nil
                       (catch clojure.lang.ExceptionInfo error
                         (ex-message error)))
                     count-after
                     (count (weaver/list rt
                                         [:= [:attr "harness/run"] "true"]
                                         {}))]
                 {:transport (attr (:strand started)
                                   :harness/guidance-transport)
                  :attempt-state
                  (get (first (guidance/attempt-records (:strand started)))
                       "state")
                  :guidance-env
                  (json/read-str
                   (get-in process-spec [:env "MILLSTRAND_MANAGED_GUIDANCE"]))
                  :developer-instructions
                  (some #(when (str/starts-with? % "developer_instructions=") %)
                        (:argv prepared))
                  :native-error native-error
                  :prepublication-no-write (= count-before count-after)})))]
        (is (= "legacy" (:transport result)))
        (is (= "not-required" (:attempt-state result)))
        (is (= {"schema" "millstrand.agent-guidance-bootstrap/v1"
                "transport" "legacy"}
               (:guidance-env result)))
        (is (string? (:developer-instructions result)))
        (is (re-find #"no accepted production capability"
                     (:native-error result)))
        (is (true? (:prepublication-no-write result)))))))

(deftest native-bundle-and-receipts-are-frozen-fenced-and-attempt-scoped
  (with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do lifecycle-setup
              '(binding [capability/*test-capability-profiles* [profile]
                         capability/*test-preflight-runner* accepted-runner]
                 (let [run (harnesses/create!
                            rt {:harness :native-codex
                                :mode :headless
                                :cwd "/tmp"
                                :prompt "Native bundle fixture"
                                :append-system-prompt "same"
                                :attributes
                                {:harness/appended-system-prompts
                                 ["same" "Run {{RUN_ID}}."]}
                                :guidance-transport "native-v1"})
                       started (harnesses/begin-attempt! rt (:id run))
                       bootstrap (harnesses/managed-bootstrap rt (:id run))
                       guidance-bootstrap (guidance/bootstrap (:strand started))
                       no-guidance-error
                       (try
                         (harnesses/managed-startup!
                          rt {:harness "codex" :native-session-id "thread-1"
                              :cwd "/tmp" :scope "root"
                              :bootstrap bootstrap})
                         nil
                         (catch clojure.lang.ExceptionInfo error
                           (ex-message error)))
                       before-fetch (weaver/show rt (:id run))
                       bundle (harnesses/managed-startup!
                               rt {:harness "codex"
                                   :native-session-id "thread-1"
                                   :cwd "/tmp" :scope "root"
                                   :bootstrap bootstrap
                                   :guidance guidance-bootstrap})
                       receipt (guidance-receipt bundle "adapter-handoff")
                       acknowledged (harnesses/guidance-acknowledge! rt receipt)
                       before-replay (weaver/show rt (:id run))
                       replayed (harnesses/guidance-acknowledge! rt receipt)
                       after-replay (weaver/show rt (:id run))
                       stale (harnesses/guidance-acknowledge!
                              rt (assoc receipt "attempt" 99))
                       after-stale (weaver/show rt (:id run))]
                   {:run-id (:id run)
                    :no-guidance-error no-guidance-error
                    :attached-before-fetch (attr before-fetch
                                                 :harness/native-attached)
                    :bundle bundle
                    :context (attr after-replay :harness/guidance-context)
                    :template (attr after-replay
                                    :harness/guidance-context-template)
                    :digest (attr after-replay
                                  :harness/guidance-bundle-sha256)
                    :acknowledged acknowledged
                    :replayed replayed
                    :stale stale
                    :replay-no-write (= before-replay after-replay)
                    :stale-no-write (= after-replay after-stale)
                    :attempts (attr after-stale
                                    :harness/guidance-attempts)}))))]
        (is (re-find #"requires --guidance" (:no-guidance-error result)))
        (is (= "false" (:attached-before-fetch result)))
        (is (= "millstrand.agent-guidance-bundle/v1"
               (get-in result [:bundle :schema])))
        (is (= ["same" "Run {{RUN_ID}}." "same"]
               (or (get-in result [:template "appended-system-prompts"])
                   (get-in result [:template :appended-system-prompts]))))
        (let [appends (or (get-in result [:context "appended-system-prompts"])
                          (get-in result [:context :appended-system-prompts]))]
          (is (= 3 (count appends)))
          (is (= "same" (first appends) (last appends)))
          (is (str/includes? (second appends) (:run-id result))))
        (is (= 64 (count (:digest result))))
        (is (= "recorded" (get-in result [:acknowledged :result])))
        (is (= "replayed" (get-in result [:replayed :result])))
        (is (= "ignored" (get-in result [:stale :result])))
        (is (true? (:replay-no-write result)))
        (is (true? (:stale-no-write result)))
        (is (= ["acknowledged"]
               (mapv #(or (get % "state") (get % :state))
                     (:attempts result))))))))

(deftest native-failure-is-sticky-and-never-fabricates-attachment
  (with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do lifecycle-setup
              '(binding [capability/*test-capability-profiles* [profile]
                         capability/*test-preflight-runner* accepted-runner]
                 (let [run (harnesses/create!
                            rt {:harness :native-codex :mode :headless
                                :cwd "/tmp" :prompt "Native failure fixture"
                                :guidance-transport "native-v1"})
                       started (harnesses/begin-attempt! rt (:id run))
                       guidance-bootstrap (guidance/bootstrap (:strand started))
                       receipt {"schema" "millstrand.agent-guidance-receipt/v1"
                                "run-id" (:id run)
                                "attempt" (:attempt started)
                                "invocation" (:invocation started)
                                "harness" "codex"
                                "transport" "native-v1"
                                "bundle-sha256" (attr run
                                                      :harness/guidance-bundle-sha256)
                                "capability-sha256"
                                (attr run :harness/guidance-capability-sha256)
                                "outcome" "failed"
                                "stage" "startup"
                                "code" "missing-hook"
                                "diagnostic" "reviewed hook did not run"}
                       recorded (harnesses/guidance-fail! rt receipt)
                       before-replay (weaver/show rt (:id run))
                       replayed (harnesses/guidance-fail! rt receipt)
                       after-replay (weaver/show rt (:id run))
                       late-ack (harnesses/guidance-acknowledge!
                                 rt (-> receipt
                                        (dissoc "stage" "code" "diagnostic")
                                        (assoc "native-session-id" "invented"
                                               "outcome" "adapter-handoff")))
                       failed (weaver/show rt (:id run))
                       unacked (harnesses/create!
                                rt {:harness :native-codex :mode :headless
                                    :cwd "/tmp" :prompt "Unacknowledged fixture"
                                    :guidance-transport "native-v1"})
                       unacked-start
                       (harnesses/begin-attempt! rt (:id unacked))
                       unacked-finish
                       (harnesses/finish!
                        rt (:id unacked)
                        {:status :done :exit-code 0
                         :session-id "observed-but-unattached"
                         :session-usable true
                         :invocation (:invocation unacked-start)
                         :evidence {:settled true
                                    :settlement "process-exit"}})]
                   {:guidance-bootstrap guidance-bootstrap
                    :recorded recorded :replayed replayed :late-ack late-ack
                    :no-write (= before-replay after-replay)
                    :status (attr failed :harness/status)
                    :substatus (attr failed :harness/substatus)
                    :attached (attr failed :harness/native-attached)
                    :session (attr failed :harness/session-id)
                    :stop-reason (attr failed :harness/stop-reason)
                    :unacked-status (attr unacked-finish :harness/status)
                    :unacked-substatus (attr unacked-finish
                                             :harness/substatus)
                    :unacked-settlement (attr unacked-finish
                                              :harness/settlement)
                    :unacked-stop-requested
                    (attr unacked-finish :harness/stop-requested-at)
                    :unacked-session-usable
                    (attr unacked-finish :harness/session-usable)
                    :unacked-attached (attr unacked-finish
                                            :harness/native-attached)}))))]
        (is (= "native-v1"
               (get-in result [:guidance-bootstrap "transport"])))
        (is (= "recorded" (get-in result [:recorded :result])))
        (is (= "replayed" (get-in result [:replayed :result])))
        (is (= "ignored" (get-in result [:late-ack :result])))
        (is (true? (:no-write result)))
        (is (= ["failed" "bootstrap"]
               [(:status result) (:substatus result)]))
        (is (= "false" (:attached result)))
        (is (not= "invented" (:session result)))
        (is (= "native guidance bootstrap failed" (:stop-reason result)))
        (is (= ["failed" "bootstrap" "process-exit" "false" "false"]
               [(:unacked-status result) (:unacked-substatus result)
                (:unacked-settlement result) (:unacked-session-usable result)
                (:unacked-attached result)]))
        (is (string? (:unacked-stop-requested result)))))))

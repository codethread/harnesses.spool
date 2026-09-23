(ns ct.spools.harnesses.guidance-test
  "Focused native managed-guidance protocol and lifecycle tests."
  (:require [millstrand.test.alpha :as test-alpha]))

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
     (def fixture-root
       (java.nio.file.Files/createDirectories
        (.resolve (java.nio.file.Path/of
                   (get-in rt [:metadata :config-dir])
                   (make-array String 0))
                  "test-fixtures")
        (make-array java.nio.file.attribute.FileAttribute 0)))
     (def fixture-dir
       (.toFile
        (java.nio.file.Files/createTempDirectory
         fixture-root "guidance-profile-"
         (make-array java.nio.file.attribute.FileAttribute 0))))
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
     (def pi-capability-document
       (assoc capability-document
              "harness" "pi"
              "host-version" "0.84.4-test"
              "max-context-bytes" 65536
              "hook-fact"
              {"host-package" "@mariozechner/pi-coding-agent"
               "host-package-version" "0.84.4-test"
               "host-package-sha256" (apply str (repeat 64 "c"))
               "extensions"
               [{"entrypoint" "/test/pi/managed-guidance.ts"
                 "closure-sha256" (apply str (repeat 64 "d"))}]
               "prompt-owner-entrypoint" "/test/pi/managed-guidance.ts"
               "system-prompt-options-contract" "owned-v1"}))
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

(ns ct.spools.harnesses.guidance-capability-test
  "Exact disposable capability admission tests for disabled native guidance."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.strict-json :as strict-json]))

(defn- codex-hook []
  {"eventName" "sessionStart"
   "key" "managed-guidance"
   "source" "plugin"
   "sourcePath" "/fixture/plugin/hooks.json"
   "pluginId" "agents-fixture"
   "command" "node managed-guidance.js"
   "enabled" true
   "trustStatus" "trusted"
   "currentHash" "trusted-current-hash"
   "timeoutSec" 15
   "additionalContextLimit" 4096})

(defn- pi-hook []
  {"host-package" "@earendil-works/pi-coding-agent"
   "host-package-version" "0.84.4"
   "host-package-sha256" (str/join (repeat 64 "c"))
   "extensions"
   [{"entrypoint" "/fixture/system-prompt/index.ts"
     "closure-sha256" (str/join (repeat 64 "d"))}]
   "prompt-owner-entrypoint" "/fixture/system-prompt/index.ts"
   "system-prompt-options-contract" "owned-v1"})

(defn- capability-document [harness executable-sha]
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" harness
   "adapter-contract" "native-v1"
   "adapter-sha256" (str/join (repeat 64 "a"))
   "executable-sha256" executable-sha
   "host-version" (if (= "codex" harness) "0.154.0" "0.84.4")
   "launch-profile-sha256" (str/join (repeat 64 "b"))
   "max-context-bytes" (if (= "codex" harness) 3072 65536)
   "hook-fact" (if (= "codex" harness) (codex-hook) (pi-hook))})

(defn- with-profile [harness f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "guidance-capability"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        scripts (doto (java.io.File. root "scripts") .mkdirs)
        preflight (java.io.File. scripts "managed-guidance-preflight.mjs")
        _ (spit preflight "// exact disposable preflight\n")
        executable "/usr/bin/true"
        document (capability-document harness
                                      (capability/file-sha256 executable))
        profile {:harness harness
                 :preflight {:path (.getCanonicalPath preflight)
                             :sha256 (capability/file-sha256 preflight)}
                 :capability document}
        request {"harness" harness
                 "executable" executable
                 "mode" "headless"
                 "cwd" (.getCanonicalPath root)
                 "workspace" (.getCanonicalPath root)
                 "env" {}
                 "extra-argv" []
                 "resumes" false}]
    (try
      (f {:profile profile :document document :request request})
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn- result-json [document]
  (strict-json/canonical-json
   {"schema" "millstrand.agent-guidance-preflight/v1"
    "result" "capable"
    "capability" document}))

(defn- process-result [profile stdout]
  {:source (:preflight profile)
   :exit-code 0
   :stdout stdout
   :stderr ""})

(deftest exact-codex-and-pi-test-profiles-are-admitted
  (doseq [harness ["codex" "pi"]]
    (with-profile
      harness
      (fn [{:keys [profile document request]}]
        (binding [capability/*test-capability-profiles* [profile]
                  capability/*test-preflight-runner*
                  (fn [accepted _]
                    (process-result accepted (result-json document)))]
          (is (= document (capability/preflight! request))))))))

(deftest malformed-changed-nonzero-and-legacy-required-evidence-fails-loudly
  (with-profile
    "codex"
    (fn [{:keys [profile document request]}]
      (doseq [[label process]
              [["malformed" (process-result profile "{nope}")]
               ["duplicate-key"
                (process-result
                 profile
                 (str "{\"schema\":\"millstrand.agent-guidance-preflight/v1\","
                      "\"result\":\"capable\",\"result\":\"capable\","
                      "\"capability\":{}}"))]
               ["changed"
                (process-result
                 profile
                 (result-json (assoc document "host-version" "0.154.1")))]
               ["oversized"
                (process-result profile
                                (str "{\"padding\":\""
                                     (str/join (repeat 70000 "x")) "\"}"))]
               ["nonzero" (assoc (process-result profile "")
                                 :exit-code 2 :stderr "probe failed")]
               ["duplicate-injector"
                (process-result
                 profile
                 (strict-json/canonical-json
                  {"schema" "millstrand.agent-guidance-preflight/v1"
                   "result" "legacy-required"
                   "code" "duplicate-injector"
                   "diagnostic" "two effective registrations"}))]]]
        (testing label
          (binding [capability/*test-capability-profiles* [profile]
                    capability/*test-preflight-runner* (fn [_ _] process)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (capability/preflight! request)))))))))

(deftest changed-preflight-source-is-rejected-before-execution
  (with-profile
    "codex"
    (fn [{:keys [profile request]}]
      (let [called? (atom false)]
        (spit (get-in profile [:preflight :path]) "// changed after acceptance\n")
        (binding [capability/*test-capability-profiles* [profile]
                  capability/*test-preflight-runner*
                  (fn [_ _] (reset! called? true))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"does not match the accepted artifact"
                                (capability/preflight! request)))
          (is (false? @called?)))))))

(deftest exact-preflight-command-has-a-hard-timeout
  (with-profile
    "codex"
    (fn [{:keys [profile request]}]
      (let [path (get-in profile [:preflight :path])
            _ (spit path "setTimeout(() => {}, 10000);\n")
            profile (assoc-in profile [:preflight :sha256]
                              (capability/file-sha256 path))]
        (binding [capability/*test-capability-profiles* [profile]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"timed out"
                                (capability/preflight! request))))))))

(deftest untrusted-and-duplicate-approved-sources-are-rejected
  (with-profile
    "codex"
    (fn [{:keys [profile document request]}]
      (let [untrusted (assoc-in document ["hook-fact" "trustStatus"]
                                "bypassed")]
        (binding [capability/*test-capability-profiles*
                  [(assoc profile :capability untrusted)]
                  capability/*test-preflight-runner*
                  (fn [accepted _]
                    (process-result accepted (result-json untrusted)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"not trusted"
                                (capability/preflight! request))))
        (binding [capability/*test-capability-profiles* [profile profile]
                  capability/*test-preflight-runner*
                  (fn [accepted _]
                    (process-result accepted (result-json document)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"duplicate accepted"
                                (capability/preflight! request))))))))

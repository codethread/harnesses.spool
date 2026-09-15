(ns ct.spools.harnesses.guidance-capability-deadline-test
  "Single-budget native capability admission timeout regressions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline]
            [ct.spools.harnesses.internal.strict-json :as strict-json]))

(defn- capability-document [executable-sha]
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" "codex"
   "adapter-contract" "native-v1"
   "adapter-sha256" (str/join (repeat 64 "a"))
   "executable-sha256" executable-sha
   "host-version" "0.154.0"
   "launch-profile-sha256" (str/join (repeat 64 "b"))
   "max-context-bytes" 3072
   "hook-fact"
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
    "additionalContextLimit" 4096}})

(defn- finalize-profile [profile]
  (assoc-in profile [:process-ownership :reviewed-closure-sha256]
            (capability/process-ownership-sha256 profile)))

(defn- fixture [operation]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "guidance-deadline"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        scripts (doto (io/file root "scripts") .mkdirs)
        preflight (io/file scripts "managed-guidance-preflight.mjs")
        _ (spit preflight "// exact disposable preflight\n")
        executable "/usr/bin/true"
        environment {}
        document (capability-document
                  (capability/file-sha256 executable))
        interpreter (capability/resolve-executable "node" (System/getenv))
        profile
        (finalize-profile
         {:harness "codex"
          :preflight {:path (.getCanonicalPath preflight)
                      :sha256 (capability/file-sha256 preflight)}
          :capability document
          :executable-closure
          {:schema "millstrand.local-guidance-executable-closure/v1"
           :reviewed-complete true
           :resolver-policy (closure/resolver-policy)
           :artifacts [(closure/artifact "entrypoint" preflight)
                       (closure/artifact "interpreter" interpreter)
                       (closure/artifact "ownership-scanner" "/bin/ps")]
           :resolution-inputs
           {:cwd (.getCanonicalPath root)
            :environment (closure/resolution-environment environment)}}
          :process-ownership
          {:contract "private-posix-session/inherited-process-group-v1"
           :reviewed-closure-sha256 (str/join (repeat 64 "0"))
           :child-process-behavior "inherited-process-group-only"}})
        request {"harness" "codex"
                 "executable" executable
                 "mode" "headless"
                 "cwd" (.getCanonicalPath root)
                 "workspace" (.getCanonicalPath root)
                 "env" environment
                 "extra-argv" []
                 "resumes" false}
        result
        {:source (:preflight profile)
         :reviewed-closure-sha256
         (get-in profile [:process-ownership :reviewed-closure-sha256])
         :exit-code 0
         :stdout
         (strict-json/canonical-json
          {"schema" "millstrand.agent-guidance-preflight/v1"
           "result" "capable"
           "capability" document})
         :stderr ""}]
    (try
      (operation {:profile profile :request request :result result})
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn- timed [operation]
  (let [started (System/nanoTime)
        error (try
                (operation)
                nil
                (catch Throwable error error))]
    {:error error
     :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)}))

(defn- admission-worker-count []
  (->> (.keySet (Thread/getAllStackTraces))
       (filter #(and (.isAlive ^Thread %)
                     (= "guidance-admission-worker" (.getName ^Thread %))))
       count))

(defn- assert-timeout! [{:keys [error elapsed-ms]}]
  (is (re-find #"Guidance preflight timed out" (ex-message error)))
  (is (< elapsed-ms 3600.0))
  (is (zero? (admission-worker-count))))

(deftest one-budget-fences-hashing-verification-start-and-execution
  (fixture
   (fn [{:keys [profile request result]}]
     (let [runner-calls (atom 0)
           runner (fn [& _] (swap! runner-calls inc) result)
           original-hash capability/file-sha256]
       (testing "executable hashing is cancellable before process start"
         (let [outcome
               (with-redefs [capability/file-sha256
                             (fn [path]
                               (Thread/sleep 3100)
                               (original-hash path))]
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* runner]
                   (timed #(capability/preflight! request))))]
           (assert-timeout! outcome)
           (is (zero? @runner-calls))))
       (testing "a 3.1 second closure verification starts no subprocess"
         (let [outcome
               (with-redefs [closure/verify!
                             (fn [& _]
                               (Thread/sleep 3100)
                               "late-verification")]
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* runner]
                   (timed #(capability/preflight! request))))]
           (assert-timeout! outcome)
           (is (zero? @runner-calls))))
       (testing "the final pre-start check cannot invoke the runner"
         (let [original-check deadline/check!
               outcome
               (with-redefs [deadline/check!
                             (fn [budget phase]
                               (if (= "process-execution" phase)
                                 (deadline/timed-out! "process-execution")
                                 (original-check budget phase)))]
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* runner]
                   (timed #(capability/preflight! request))))]
           (is (re-find #"Guidance preflight timed out"
                        (ex-message (:error outcome))))
           (is (zero? @runner-calls))))
       (testing "process execution cannot outlive the shared work deadline"
         (let [outcome
               (binding [capability/*test-capability-profiles* [profile]
                         capability/*test-preflight-runner*
                         (fn [& _]
                           (swap! runner-calls inc)
                           (Thread/sleep 3100)
                           result)]
                 (timed #(capability/preflight! request)))]
           (assert-timeout! outcome)
           (is (= 1 @runner-calls))))))))

(deftest post-execution-closure-and-result-validation-remain-in-budget
  (fixture
   (fn [{:keys [profile request result]}]
     (let [runner-calls (atom 0)
           runner (fn [& _] (swap! runner-calls inc) result)
           original-verify closure/verify!]
       (testing "post-execution closure verification cannot admit late"
         (let [verification-calls (atom 0)
               outcome
               (with-redefs [closure/verify!
                             (fn [& args]
                               (if (= 2 (swap! verification-calls inc))
                                 (do (Thread/sleep 3100)
                                     "late-verification")
                                 (apply original-verify args)))]
                 (binding [capability/*test-capability-profiles* [profile]
                           capability/*test-preflight-runner* runner]
                   (timed #(capability/preflight! request))))]
           (assert-timeout! outcome)
           (is (= 1 @runner-calls))))
       (testing "post-execution result validation cannot admit late"
         (let [validator
               (ns-resolve
                'ct.spools.harnesses.internal.guidance-capability
                'validate-result!)
               outcome
               (with-redefs-fn
                 {validator (fn [& _]
                              (Thread/sleep 3100)
                              (:capability profile))}
                 #(binding [capability/*test-capability-profiles* [profile]
                            capability/*test-preflight-runner* runner]
                    (timed (fn [] (capability/preflight! request)))))]
           (assert-timeout! outcome)
           (is (= 2 @runner-calls))))))))

(ns ct.spools.harnesses.guidance-closure-test
  "Real-process checks for reviewed native preflight executable closures."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.strict-json :as strict-json]))

(defn- capability-document []
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" "codex"
   "adapter-contract" "native-v1"
   "adapter-sha256" (str/join (repeat 64 "a"))
   "executable-sha256" (capability/file-sha256 "/usr/bin/true")
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

(defn- selected-environment [environment]
  (into {} (map (fn [key] [key (get environment key)]))
        ["PATH" "NODE_OPTIONS" "NODE_PATH"]))

(defn- finalize-profile [profile]
  (assoc-in profile [:process-ownership :reviewed-closure-sha256]
            (closure/reviewed-sha256 profile)))

(defn- result-source [document marker]
  (str "import fs from 'node:fs';\n"
       "import { direct } from './direct.mjs';\n"
       "fs.appendFileSync(" (strict-json/canonical-json (str marker))
       ", 'ran\\n');\n"
       "let input = '';\n"
       "process.stdin.setEncoding('utf8');\n"
       "process.stdin.on('data', chunk => input += chunk);\n"
       "process.stdin.on('end', () => {\n"
       "  JSON.parse(input);\n"
       "  if (direct !== 'direct-ok') process.exit(72);\n"
       "  process.stdout.write(" (strict-json/canonical-json
                                  (strict-json/canonical-json
                                   {"schema"
                                    "millstrand.agent-guidance-preflight/v1"
                                    "result" "capable"
                                    "capability" document})) ");\n"
       "});\n"))

(defn- with-closure [f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "guidance-closure"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        scripts (doto (io/file root "scripts") .mkdirs)
        entrypoint (io/file scripts "managed-guidance-preflight.mjs")
        direct (io/file scripts "direct.mjs")
        transitive (io/file scripts "transitive.mjs")
        selector (io/file root "package.json")
        marker (io/file root "helper-ran.log")
        document (capability-document)
        direct-source
        "import { transitive } from './transitive.mjs';\nexport const direct = 'direct-' + transitive;\n"
        transitive-source "export const transitive = 'ok';\n"
        selector-source "{\"type\":\"module\"}\n"
        _ (spit direct direct-source)
        _ (spit transitive transitive-source)
        _ (spit selector selector-source)
        _ (spit entrypoint (result-source document marker))
        environment (dissoc (into {} (System/getenv))
                            "NODE_OPTIONS" "NODE_PATH")
        interpreter (capability/resolve-executable "node" environment)
        profile
        (finalize-profile
         {:harness "codex"
          :preflight {:path (.getCanonicalPath entrypoint)
                      :sha256 (closure/file-sha256 entrypoint)}
          :capability document
          :executable-closure
          {:schema "millstrand.local-guidance-executable-closure/v1"
           :reviewed-complete true
           :artifacts
           [(closure/artifact "entrypoint" entrypoint)
            (closure/artifact "import" direct)
            (closure/artifact "import" transitive)
            (closure/artifact "selector" selector)
            (closure/artifact "interpreter" interpreter)
            (closure/artifact "ownership-scanner" "/bin/ps")]
           :resolution-inputs
           {:cwd (.getCanonicalPath root)
            :environment (selected-environment environment)}}
          :process-ownership
          {:contract "private-posix-session/inherited-process-group-v1"
           :reviewed-closure-sha256 (str/join (repeat 64 "0"))
           :child-process-behavior "inherited-process-group-only"}})
        request {"harness" "codex"
                 "executable" "/usr/bin/true"
                 "mode" "headless"
                 "cwd" (.getCanonicalPath root)
                 "workspace" (.getCanonicalPath root)
                 "env" environment
                 "extra-argv" []
                 "resumes" false}]
    (try
      (f {:profile profile
          :request request
          :document document
          :entrypoint entrypoint
          :direct direct
          :direct-source direct-source
          :transitive transitive
          :transitive-source transitive-source
          :selector selector
          :selector-source selector-source
          :marker marker})
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn- preflight [profile request]
  (binding [capability/*test-capability-profiles* [profile]]
    (capability/preflight! request)))

(defn- failure [profile request]
  (try
    (preflight profile request)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- marker-content [marker]
  (if (.isFile marker) (slurp marker) ""))

(deftest direct-transitive-and-resolution-changes-precede-helper-execution
  (with-closure
    (fn [{:keys [profile request document direct direct-source transitive
                 transitive-source selector selector-source marker]}]
      (is (= document (preflight profile request)))
      (is (= "ran\n" (marker-content marker)))

      (testing "direct import bytes"
        (spit direct (str/replace direct-source "direct-" "mutate-"))
        (is (re-find #"artifact bytes changed"
                     (ex-message (failure profile request))))
        (is (= "ran\n" (marker-content marker)))
        (spit direct direct-source))

      (testing "transitive import bytes"
        (spit transitive (str/replace transitive-source "'ok'" "'no'"))
        (is (re-find #"artifact bytes changed"
                     (ex-message (failure profile request))))
        (is (= "ran\n" (marker-content marker)))
        (spit transitive transitive-source))

      (testing "selector bytes"
        (spit selector (str/replace selector-source "module" "common"))
        (is (re-find #"artifact bytes changed"
                     (ex-message (failure profile request))))
        (is (= "ran\n" (marker-content marker)))
        (spit selector selector-source))

      (testing "resolution environment"
        (is (re-find #"resolution inputs changed"
                     (ex-message
                      (failure profile
                               (assoc-in request ["env" "NODE_OPTIONS"]
                                         "--require=changed.cjs")))))
        (is (= "ran\n" (marker-content marker)))))))

(deftest missing-incomplete-and-unsupported-closures-never-run-helper
  (with-closure
    (fn [{:keys [profile request direct direct-source marker]}]
      (testing "missing artifact"
        (.delete direct)
        (is (re-find #"artifact is missing"
                     (ex-message (failure profile request))))
        (is (= "" (marker-content marker)))
        (spit direct direct-source))

      (testing "dynamic resolution inputs"
        (let [dynamic-profile
              (finalize-profile
               (assoc-in profile
                         [:executable-closure :resolution-inputs
                          :environment "NODE_PATH"]
                         "/unreviewed/modules"))]
          (is (re-find #"unsupported dynamic resolution inputs"
                       (ex-message
                        (failure dynamic-profile
                                 (assoc-in request ["env" "NODE_PATH"]
                                           "/unreviewed/modules")))))
          (is (= "" (marker-content marker)))))

      (testing "incomplete required roles"
        (let [incomplete
              (finalize-profile
               (update-in profile [:executable-closure :artifacts]
                          #(vec (remove (fn [artifact]
                                          (= "ownership-scanner"
                                             (:role artifact)))
                                        %))))]
          (is (re-find #"missing a required artifact"
                       (ex-message (failure incomplete request))))
          (is (= "" (marker-content marker)))))

      (testing "missing or unsupported process ownership"
        (doseq [[unsupported message]
                [[(dissoc profile :process-ownership)
                  #"local capability profile"]
                 [(assoc-in profile
                            [:process-ownership :reviewed-closure-sha256]
                            (str/join (repeat 64 "f")))
                  #"does not match its reviewed closure"]
                 [(finalize-profile
                   (assoc-in profile
                             [:process-ownership :child-process-behavior]
                             "may-create-detached-sessions"))
                  #"children to escape"]]]
          (is (re-find message
                       (ex-message (failure unsupported request))))
          (is (= "" (marker-content marker))))))))

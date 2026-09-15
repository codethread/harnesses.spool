(ns ct.spools.harnesses.guidance-closure-test
  "Real-process checks for reviewed native preflight executable closures."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.strict-json :as strict-json])
  (:import [java.lang ProcessBuilder$Redirect]))

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
                            "NODE_OPTIONS" "NODE_PATH" "OPENSSL_CONF")
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
           :resolver-policy (closure/resolver-policy)
           :artifacts
           [(closure/artifact "entrypoint" entrypoint)
            (closure/artifact "import" direct)
            (closure/artifact "import" transitive)
            (closure/artifact "selector" selector)
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

(defn- compile-constructor! [root]
  (let [source (io/file root "constructor.c")
        library (io/file root "constructor.dylib")
        _ (spit source
                (str "#include <fcntl.h>\n"
                     "#include <stdlib.h>\n"
                     "#include <string.h>\n"
                     "#include <unistd.h>\n"
                     "__attribute__((constructor)) static void mark(void) {\n"
                     "  const char *path = getenv(\"GUIDANCE_CONSTRUCTOR_MARKER\");\n"
                     "  if (path) {\n"
                     "    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);\n"
                     "    if (fd >= 0) { write(fd, \"constructor\\n\", 12); close(fd); }\n"
                     "  }\n"
                     "}\n"))
        process (.start (ProcessBuilder.
                         ^java.util.List
                         ["/usr/bin/clang" "-dynamiclib" "-o"
                          (.getCanonicalPath library)
                          (.getCanonicalPath source)]))]
    (when-not (and (.waitFor process 30 java.util.concurrent.TimeUnit/SECONDS)
                   (zero? (.exitValue process)))
      (throw (ex-info "Unable to compile constructor fixture"
                      {:exit-code (when-not (.isAlive process)
                                    (.exitValue process))})))
    library))

(defn- openssl-configuration! [root library]
  (let [configuration (io/file root "openssl.cnf")]
    (spit configuration
          (str "nodejs_conf = nodejs_init\n"
               "[nodejs_init]\n"
               "providers = providers\n"
               "[providers]\n"
               "fixture = fixture_provider\n"
               "[fixture_provider]\n"
               "module = " (.getCanonicalPath library) "\n"
               "activate = 1\n"))
    configuration))

(defn- run-openssl-control! [interpreter environment]
  (let [builder (doto (ProcessBuilder. ^java.util.List [interpreter "-e" ""])
                  (.redirectOutput ProcessBuilder$Redirect/DISCARD)
                  (.redirectError ProcessBuilder$Redirect/DISCARD))
        _ (doto (.environment builder)
            (.clear)
            (.putAll environment))
        process (.start builder)]
    (when-not (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "OpenSSL constructor positive control timed out" {})))))

(defn- run-constructor-control! [interpreter environment]
  (let [builder (ProcessBuilder. ^java.util.List [interpreter "-e" ""])
        _ (doto (.environment builder)
            (.clear)
            (.putAll environment))
        process (.start builder)]
    (when-not (and (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)
                   (zero? (.exitValue process)))
      (when (.isAlive process) (.destroyForcibly process))
      (throw (ex-info "Constructor positive control failed" {})))))

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

(deftest openssl-configuration-is-rejected-before-any-reviewed-execution
  (with-closure
    (fn [{:keys [profile request marker]}]
      (let [root (io/file (get request "cwd"))
            constructor-marker (io/file root "openssl-constructor-ran.log")
            scanner-marker (io/file root "scanner-ran.log")
            scanner (io/file root "scanner.sh")
            _ (spit scanner
                    (str "#!/bin/sh\n"
                         "printf 'scanner\\n' >> " (pr-str (str scanner-marker))
                         "\nexec /bin/ps \"$@\"\n"))
            _ (.setExecutable scanner true)
            profile
            (finalize-profile
             (update-in
              profile [:executable-closure :artifacts]
              (fn [artifacts]
                (conj
                 (mapv #(if (= "ownership-scanner" (:role %))
                          (closure/artifact "ownership-scanner" scanner)
                          %)
                       artifacts)
                 (closure/artifact "subprocess" "/bin/ps")))))
            library (compile-constructor! root)
            configuration (openssl-configuration! root library)
            configured-environment
            (assoc (get request "env")
                   "OPENSSL_CONF" (.getCanonicalPath configuration)
                   "GUIDANCE_CONSTRUCTOR_MARKER"
                   (.getCanonicalPath constructor-marker))]
        (run-openssl-control!
         (closure/artifact-path profile "interpreter") configured-environment)
        (is (= "constructor\n" (marker-content constructor-marker)))
        (.delete constructor-marker)
        (doseq [[label rejected environment]
                [["present" profile configured-environment]
                 ["omitted policy key"
                  (finalize-profile
                   (update-in profile
                              [:executable-closure :resolver-policy
                               :environment]
                              dissoc "OPENSSL_CONF"))
                  (get request "env")]
                 ["omitted evidence key"
                  (finalize-profile
                   (update-in profile
                              [:executable-closure :resolution-inputs
                               :environment]
                              dissoc "OPENSSL_CONF"))
                  (get request "env")]
                 ["attempted manifest authorization"
                  (finalize-profile
                   (-> profile
                       (assoc-in [:executable-closure :resolution-inputs
                                  :environment "OPENSSL_CONF"]
                                 (.getCanonicalPath configuration))
                       (update-in [:executable-closure :artifacts]
                                  conj (closure/artifact "selector"
                                                         configuration))))
                  configured-environment]]]
          (testing label
            (is (thrown? clojure.lang.ExceptionInfo
                         (preflight rejected
                                    (assoc request "env" environment))))
            (is (= "" (marker-content constructor-marker)))
            (is (= "" (marker-content scanner-marker)))
            (is (= "" (marker-content marker)))))
        (is (re-find #"unsupported dynamic resolution inputs"
                     (ex-message
                      (failure profile
                               (assoc-in request ["env" "OPENSSL_CONF"] "")))))
        (is (= "" (marker-content constructor-marker)))
        (is (= "" (marker-content scanner-marker)))
        (is (= "" (marker-content marker)))))))

(deftest loader-injection-is-rejected-before-constructor-or-helper-execution
  (with-closure
    (fn [{:keys [profile request marker]}]
      (let [root (io/file (get request "cwd"))
            constructor-marker (io/file root "constructor-ran.log")
            library (compile-constructor! root)
            injected-environment
            (assoc (get request "env")
                   "DYLD_INSERT_LIBRARIES" (.getCanonicalPath library)
                   "GUIDANCE_CONSTRUCTOR_MARKER"
                   (.getCanonicalPath constructor-marker))]
        (run-constructor-control!
         (closure/artifact-path profile "interpreter")
         injected-environment)
        (is (= "constructor\n" (marker-content constructor-marker)))
        (.delete constructor-marker)
        (is (re-find #"unsupported dynamic resolution inputs"
                     (ex-message
                      (failure profile
                               (assoc request "env" injected-environment)))))
        (is (= "" (marker-content constructor-marker)))
        (is (= "" (marker-content marker)))))))

(deftest missing-incomplete-and-unsupported-closures-never-run-helper
  (with-closure
    (fn [{:keys [profile request direct direct-source marker]}]
      (testing "missing artifact"
        (.delete direct)
        (is (re-find #"artifact is missing"
                     (ex-message (failure profile request))))
        (is (= "" (marker-content marker)))
        (spit direct direct-source))

      (testing "mandatory resolver policy and selected keys"
        (doseq [incomplete
                [(finalize-profile
                  (update profile :executable-closure
                          dissoc :resolver-policy))
                 (finalize-profile
                  (update-in profile
                             [:executable-closure :resolver-policy
                              :environment]
                             dissoc "DYLD_INSERT_LIBRARIES"))
                 (finalize-profile
                  (update-in profile
                             [:executable-closure :resolution-inputs
                              :environment]
                             dissoc "DYLD_INSERT_LIBRARIES"))]]
          (is (re-find #"invalid keys|resolver policy is unsupported|environment is incomplete"
                       (ex-message (failure incomplete request))))
          (is (= "" (marker-content marker)))))

      (testing "absent and empty resolver inputs remain distinct"
        (let [absent-path-profile
              (finalize-profile
               (assoc-in profile
                         [:executable-closure :resolution-inputs
                          :environment "PATH"] nil))
              absent-path-environment (dissoc (get request "env") "PATH")]
          (is (string? (closure/verify! absent-path-profile
                                        absent-path-environment)))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"resolution inputs changed"
               (closure/verify! absent-path-profile
                                (assoc absent-path-environment "PATH" "")))))
        (doseq [[key value message]
                [["DYLD_INSERT_LIBRARIES" ""
                  #"unsupported dynamic resolution inputs"]
                 ["NODE_PATH" ""
                  #"resolution inputs changed"]]]
          (is (re-find message
                       (ex-message
                        (failure profile
                                 (assoc-in request ["env" key] value)))))
          (is (= "" (marker-content marker)))))

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
          (is (= "" (marker-content marker))))
        (let [library (io/file (get request "cwd") "listed.dylib")
              _ (spit library "unsafe")
              listed
              (finalize-profile
               (-> profile
                   (assoc-in [:executable-closure :resolution-inputs
                              :environment "DYLD_INSERT_LIBRARIES"]
                             (.getCanonicalPath library))
                   (update-in [:executable-closure :artifacts]
                              conj (closure/artifact "selector" library))))]
          (is (re-find #"unsupported dynamic resolution inputs"
                       (ex-message
                        (failure
                         listed
                         (assoc-in request ["env" "DYLD_INSERT_LIBRARIES"]
                                   (.getCanonicalPath library))))))
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

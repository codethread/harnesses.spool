(ns ct.spools.harnesses.agent-bin-test
  "Shell-boundary tests for the tracked interactive agent bin."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- create-temp-dir []
  (.toFile
   (Files/createTempDirectory
    (.toPath (io/file "/tmp"))
    "harness-agent-bin-"
    (make-array FileAttribute 0))))

(defn- delete-tree! [root]
  (when (.exists root)
    (with-open [paths (Files/walk
                       (.toPath root)
                       (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (sort-by #(.getNameCount %) >
                            (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists path)))))

(defn- executable! [file content]
  (spit file content)
  (when-not (.setExecutable file true false)
    (throw (ex-info "Could not make test fixture executable"
                    {:file (.getPath file)})))
  file)

(defn- command-result [argv environment]
  (let [builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.redirectErrorStream true))
        process-environment (.environment builder)]
    (doseq [[name value] environment]
      (.put process-environment name value))
    (let [process (.start builder)
          output (slurp (.getInputStream process))]
      {:exit (.waitFor process) :output output})))

(defn- nul-argv [file]
  (let [content (String. (Files/readAllBytes (.toPath file))
                         StandardCharsets/UTF_8)]
    (str/split (subs content 0 (dec (count content))) #"\u0000")))

(deftest provider-tail-crosses-shell-boundary-as-ordered-argv
  (let [root (create-temp-dir)
        workspace (io/file root "workspace")
        fake-bin (io/file root "bin")
        log-dir (io/file root "calls")
        launcher (io/file root "launcher")
        agent-bin (.getCanonicalPath
                   (io/file (System/getProperty "user.dir") "bin/agent"))]
    (try
      (.mkdirs workspace)
      (.mkdirs fake-bin)
      (.mkdirs log-dir)
      (executable!
       (io/file fake-bin "strand")
       (str "#!/bin/sh\n"
            "set -eu\n"
            ": \"${AGENT_BIN_TEST_LOG:?}\"\n"
            ": \"${AGENT_BIN_TEST_LAUNCHER:?}\"\n"
            "count_file=\"$AGENT_BIN_TEST_LOG/count\"\n"
            "if [ -f \"$count_file\" ]; then count=$(cat \"$count_file\"); else count=0; fi\n"
            "count=$((count + 1))\n"
            "printf '%s' \"$count\" > \"$count_file\"\n"
            "printf '%s\\0' \"$@\" > \"$AGENT_BIN_TEST_LOG/call-$count\"\n"
            "if [ \"${1:-}\" = --workspace ]; then shift 2; fi\n"
            "case \"${1:-}:${2:-}\" in\n"
            "  agent:run) printf '{\"id\":\"run-1\",\"launcher\":\"%s\"}\\n' \"$AGENT_BIN_TEST_LAUNCHER\" ;;\n"
            "  agent:_started) printf '{\"invocation\":\"inv-1\"}\\n' ;;\n"
            "  agent:_provider_started|agent:_finished) printf '{}\\n' ;;\n"
            "  *) printf 'unexpected strand call\\n' >&2; exit 2 ;;\n"
            "esac\n"))
      (executable!
       launcher
       (str "#!/bin/sh\n"
            "set -eu\n"
            ": \"${AGENT_BIN_TEST_LOG:?}\"\n"
            "touch \"$AGENT_BIN_TEST_LOG/launcher-ran\"\n"))
      (let [path (str (.getCanonicalPath fake-bin)
                      java.io.File/pathSeparator
                      (System/getenv "PATH"))
            result
            (command-result
             [agent-bin "pi"
              "--cwd" "/tmp/working dir"
              "--prompt" "wrapper prompt"
              "--"
              "--provider-flag" "value with spaces" "" "--help" "--"
              ":stdin" ":payload/example"
              "Keep {{RUN_ID}} and {{AGENT_ID}} literal"]
             {"PATH" path
              "MILLSTRAND_WORKSPACE" (.getCanonicalPath workspace)
              "AGENT_BIN_TEST_LOG" (.getCanonicalPath log-dir)
              "AGENT_BIN_TEST_LAUNCHER" (.getCanonicalPath launcher)})]
        (is (zero? (:exit result)) (:output result))
        (testing "the first delimiter separates wrapper and provider arguments"
          (is (= ["--workspace" (.getCanonicalPath workspace)
                  "agent" "run" "pi"
                  "--cwd" "/tmp/working dir"
                  "--prompt" "wrapper prompt"
                  "--extra-argv" "=--provider-flag"
                  "--extra-argv" "=value with spaces"
                  "--extra-argv" "="
                  "--extra-argv" "=--help"
                  "--extra-argv" "=--"
                  "--extra-argv" "=:stdin"
                  "--extra-argv" "=:payload/example"
                  "--extra-argv" "=Keep {{RUN_ID}} and {{AGENT_ID}} literal"
                  "--interactive"]
                 (nul-argv (io/file log-dir "call-1")))))
        (testing "the tracked interactive lifecycle remains intact"
          (let [started (nul-argv (io/file log-dir "call-2"))]
            (is (= ["--workspace" (.getCanonicalPath workspace)
                    "agent" "_started" "run-1" "--completion-owner-pid"]
                   (pop started)))
            (is (re-matches #"[1-9][0-9]*" (peek started))))
          (is (= ["--workspace" (.getCanonicalPath workspace)
                  "agent" "_finished" "run-1"
                  "--invocation" "inv-1" "--exit-code" "0"]
                 (nul-argv (io/file log-dir "call-3"))))
          (is (.isFile (io/file log-dir "launcher-ran")))))
      (finally
        (delete-tree! root)))))

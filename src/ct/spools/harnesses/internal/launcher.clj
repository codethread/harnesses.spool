(ns ct.spools.harnesses.internal.launcher
  "Host-TTY launcher materialization for interactive harness runs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [attr-get fail!]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(defn- sh-quote [value]
  (str "'" (str/replace (str value) "'" "'\\''") "'"))

(defn- state-root [runtime]
  (-> (io/file (get-in runtime [:metadata :state-dir]))
      .getParentFile
      .getParentFile
      .getParentFile
      .getCanonicalPath))

(defn- launcher-dir [runtime]
  (doto (io/file (get-in runtime [:metadata :state-dir]) "harness-launchers")
    (.mkdirs)))

(def ^:private bootstrap-sentinel
  "# MILLSTRAND_MANAGED_BOOTSTRAP_PENDING\nprintf '%s\\n' 'managed agent launcher was not armed' >&2\nexit 1\n")

(defn workspace
  "Return the authoritative workspace configured for `runtime`."
  [runtime]
  (or (get-in runtime [:metadata :config-dir])
      (fail! "Harness runtime has no configured workspace" {})))

(defn write!
  "Write and return a private launcher script for one interactive run.

  Codex and Pi launchers bind the child shell PID immediately before `exec`.
  An exec retains both PID and process start instant, giving reconciliation the
  actual provider-process identity rather than only its completion-owning
  parent."
  [runtime run argv env]
  (let [file (io/file (launcher-dir runtime) (str (:id run) ".sh"))
        workspace (workspace runtime)
        managed-exec? (contains? #{"codex" "pi"}
                                 (attr-get run :harness/harness))
        provider-exports (->> env
                              (sort-by key)
                              (map (fn [[name value]]
                                     (str "export " name "="
                                          (sh-quote value) "\n")))
                              (apply str))]
    (spit file
          (str "#!/bin/sh\n"
               (when managed-exec?
                 (str ": \"${MILLSTRAND_INVOCATION:?}\"\n"
                      "readonly _MILLSTRAND_HARNESS_INVOCATION="
                      "\"$MILLSTRAND_INVOCATION\"\n"))
               provider-exports
               (when (attr-get run :identity/reservation-id)
                 bootstrap-sentinel)
               "export MILLSTRAND_RUN_ID=" (sh-quote (:id run)) "\n"
               "export MILLSTRAND_AGENT_ID="
               (sh-quote (attr-get run :identity/id)) "\n"
               "export MILLSTRAND_WORKSPACE=" (sh-quote workspace) "\n"
               "export XDG_STATE_HOME=" (sh-quote (state-root runtime)) "\n"
               "cd " (sh-quote (attr-get run :harness/cwd)) " || exit 1\n"
               (when managed-exec?
                 (str "strand --workspace \"$MILLSTRAND_WORKSPACE\" "
                      "agent _provider_started \"$MILLSTRAND_RUN_ID\" "
                      "--invocation \"$_MILLSTRAND_HARNESS_INVOCATION\" "
                      "--provider-pid \"$$\" >/dev/null || exit $?\n"
                      "unset MILLSTRAND_INVOCATION\n"))
               "exec " (str/join " " (map sh-quote argv)) "\n"))
    (Files/setPosixFilePermissions
     (.toPath file)
     (PosixFilePermissions/fromString "rwx------"))
    (.getCanonicalPath file)))

(defn arm!
  "Replace one managed launcher's fail-closed sentinel with bootstrap export."
  [runtime run bootstrap]
  (let [file (io/file (launcher-dir runtime) (str (:id run) ".sh"))
        source (slurp file)
        first-index (str/index-of source bootstrap-sentinel)
        last-index (str/last-index-of source bootstrap-sentinel)]
    (when-not (and (some? first-index) (= first-index last-index))
      (fail! "Managed launcher has no unique bootstrap sentinel"
             {:run-id (:id run) :launcher (.getCanonicalPath file)}))
    (spit file
          (str/replace source bootstrap-sentinel
                       (str "export MILLSTRAND_MANAGED_BOOTSTRAP="
                            (sh-quote (json/write-str bootstrap)) "\n")))
    (.getCanonicalPath file)))

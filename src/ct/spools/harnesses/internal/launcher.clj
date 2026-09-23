(ns ct.spools.harnesses.internal.launcher
  "Host-TTY launcher materialization for interactive harness runs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.native-environment :as native-env]
            [ct.spools.harnesses.native-session :as native-session]
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

(def ^:private native-reference-sentinel
  "# MILLSTRAND_NATIVE_REFERENCE_PENDING\nprintf '%s\\n' 'managed agent launcher was not armed' >&2\nexit 1\n")

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
        codex? (= "codex" (attr-get run :harness/harness))
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
                 (str "if [ -n \"${MILLSTRAND_INVOCATION:-}\" ]; then\n"
                      "  readonly _MILLSTRAND_HARNESS_INVOCATION="
                      "\"$MILLSTRAND_INVOCATION\"\n"
                      "fi\n"))
               provider-exports
               (when codex? native-reference-sentinel)
               "export MILLSTRAND_RUN_ID=" (sh-quote (:id run)) "\n"
               (cond
                 codex?
                 "unset MILLSTRAND_AGENT_ID MILLSTRAND_MANAGED_BOOTSTRAP MILLSTRAND_MANAGED_GUIDANCE\n"
                 (not= "pi" (attr-get run :harness/harness))
                 (str "export MILLSTRAND_AGENT_ID="
                      (sh-quote (attr-get run :identity/id)) "\n"))
               "export MILLSTRAND_WORKSPACE=" (sh-quote workspace) "\n"
               "export XDG_STATE_HOME=" (sh-quote (state-root runtime)) "\n"
               "cd " (sh-quote (attr-get run :harness/cwd)) " || exit 1\n"
               (when managed-exec?
                 (str "if [ -n \"${_MILLSTRAND_HARNESS_INVOCATION:-}\" ]; then\n"
                      "  strand --workspace \"$MILLSTRAND_WORKSPACE\" "
                      "agent _provider_started \"$MILLSTRAND_RUN_ID\" "
                      "--invocation \"$_MILLSTRAND_HARNESS_INVOCATION\" "
                      "--provider-pid \"$$\" >/dev/null || exit $?\n"
                      "else\n"
                      "  strand --workspace \"$MILLSTRAND_WORKSPACE\" "
                      "agent _provider_started \"$MILLSTRAND_RUN_ID\" "
                      "--provider-pid \"$$\" >/dev/null || exit $?\n"
                      "fi\n"
                      "unset MILLSTRAND_INVOCATION\n"))
               "exec " (str/join " " (map sh-quote
                                          (if (= "pi" (attr-get run :harness/harness))
                                            (native-env/scrub-command argv) argv))) "\n"))
    (Files/setPosixFilePermissions
     (.toPath file)
     (PosixFilePermissions/fromString "rwx------"))
    (.getCanonicalPath file)))

(defn arm-native!
  "Arm a native launcher with only its current run reference."
  [runtime run]
  (let [file (io/file (launcher-dir runtime) (str (:id run) ".sh"))
        source (slurp file)]
    (when-not (str/includes? source native-reference-sentinel)
      (fail! "Native launcher has no arming sentinel" {:run-id (:id run)}))
    (spit file (str/replace source native-reference-sentinel
                            (str "export MILLSTRAND_RUN_REFERENCE="
                                 (sh-quote (native-session/reference run)) "\n")))
    (.getCanonicalPath file)))

(ns ct.spools.harnesses.internal.launcher
  "Host-TTY launcher materialization for interactive harness runs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [attr-get]])
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

(defn write!
  "Write and return a private launcher script for one interactive run."
  [runtime run argv]
  (let [file (io/file (launcher-dir runtime) (str (:id run) ".sh"))
        workspace (get-in runtime [:metadata :config-dir])]
    (spit file
          (str "#!/bin/sh\n"
               "export MILLSTRAND_RUN_ID=" (sh-quote (:id run)) "\n"
               "export MILLSTRAND_AGENT_ID="
               (sh-quote (attr-get run :identity/id)) "\n"
               "export MILLSTRAND_WORKSPACE=" (sh-quote workspace) "\n"
               "export XDG_STATE_HOME=" (sh-quote (state-root runtime)) "\n"
               "cd " (sh-quote (attr-get run :harness/cwd)) " || exit 1\n"
               "exec " (str/join " " (map sh-quote argv)) "\n"))
    (Files/setPosixFilePermissions
     (.toPath file)
     (PosixFilePermissions/fromString "rwx------"))
    (.getCanonicalPath file)))

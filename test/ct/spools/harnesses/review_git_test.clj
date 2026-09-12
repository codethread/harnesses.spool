(ns ct.spools.harnesses.review-git-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.review-git :as review-git])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays]))

(defn- path [root relative]
  (.resolve ^Path root ^String relative))

(defn- write-text! [root relative content]
  (let [target (path root relative)]
    (some-> (.getParent target)
            (Files/createDirectories (make-array FileAttribute 0)))
    (Files/writeString target content StandardCharsets/UTF_8
                       (make-array java.nio.file.OpenOption 0))
    target))

(defn- write-bytes! [root relative content]
  (let [target (path root relative)]
    (some-> (.getParent target)
            (Files/createDirectories (make-array FileAttribute 0)))
    (Files/write target ^bytes content
                 (make-array java.nio.file.OpenOption 0))
    target))

(defn- run-git [root & args]
  (let [command (into ["git"] args)
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.directory (.toFile ^Path root))
                    (.redirectErrorStream true)
                    .start)
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "Test Git command failed"
                      {:command command :exit exit :output output})))
    output))

(defn- git! [root & args]
  (apply run-git root args)
  nil)

(defn- git-out [root & args]
  (str/trim (apply run-git root args)))

(defn- commit-all! [root message]
  (git! root "add" "--all")
  (git! root "commit" "-q" "-m" message))

(defn- init-repo! [root]
  (git! root "init" "-q" "-b" "main")
  (git! root "config" "user.email" "review-git-test@example.com")
  (git! root "config" "user.name" "Review Git Test"))

(defn- delete-tree! [root]
  (when (Files/exists root (make-array LinkOption 0))
    (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (doseq [entry (reverse (vec (iterator-seq (.iterator paths))))]
        (Files/deleteIfExists ^Path entry)))))

(defn- with-temp-repo [f]
  (let [root (Files/createTempDirectory
              "review-git-test-"
              (make-array FileAttribute 0))]
    (try
      (init-repo! root)
      (f root)
      (finally
        (delete-tree! root)))))

(defn- status [root]
  (run-git root "status" "--porcelain=v1" "-z"))

(defn- index-bytes [root]
  (Files/readAllBytes (path root ".git/index")))

(defn- unchanged-index? [before after]
  (Arrays/equals ^bytes before ^bytes after))

(deftest working-tree-capture-includes-every-real-change-without-mutation
  (with-temp-repo
    (fn [root]
      (write-text! root ".gitignore" "*.ignored\n")
      (write-text! root "deleted.txt" "delete me\n")
      (write-text! root "unstaged.txt" "before\n")
      (commit-all! root "base")
      (git! root "checkout" "-q" "-b" "feature")
      (write-text! root "branch-commit.txt" "committed on feature\n")
      (commit-all! root "feature commit")
      (write-text! root "staged.txt" "staged\n")
      (git! root "add" "staged.txt")
      (write-text! root "unstaged.txt" "after\n")
      (Files/delete (path root "deleted.txt"))
      (write-text! root "untracked.txt" "untracked\n")
      (write-text! root "hidden.ignored" "ignored\n")
      (Files/createDirectories (path root "nested")
                               (make-array FileAttribute 0))
      (let [before-status (status root)
            before-index (index-bytes root)
            base-sha (git-out root "rev-parse" "main")
            expected ["branch-commit.txt"
                      "deleted.txt"
                      "staged.txt"
                      "unstaged.txt"
                      "untracked.txt"]
            automatic (review-git/capture
                       {:cwd (str (path root "nested"))})
            explicit (review-git/capture
                      {:cwd (str root) :base base-sha})]
        (is (= expected (:paths automatic)))
        (is (= expected (:paths explicit)))
        (is (= "working-tree" (:source automatic)))
        (is (= (str (.toRealPath root (make-array LinkOption 0)))
               (:repo-root automatic)))
        (is (= "main" (:base automatic)))
        (is (= base-sha (:base-sha automatic)))
        (is (= "HEAD" (:tip automatic)))
        (is (= (git-out root "rev-parse" "HEAD") (:tip-sha automatic)))
        (is (= base-sha (:base explicit)))
        (is (= base-sha (:base-sha explicit)))
        (is (= before-status (status root)))
        (is (unchanged-index? before-index (index-bytes root)))))))

(deftest local-main-is-a-valid-default-for-dirty-work
  (with-temp-repo
    (fn [root]
      (write-text! root "tracked.txt" "before\n")
      (commit-all! root "base")
      (write-text! root "tracked.txt" "after\n")
      (let [change (review-git/capture {:cwd (str root)})]
        (is (= "main" (:base change)))
        (is (= ["tracked.txt"] (:paths change)))
        (is (= (:tip-sha change) (:merge-base change)))))))

(deftest symbolic-origin-default-is-preferred
  (with-temp-repo
    (fn [root]
      (write-text! root "base.txt" "base\n")
      (commit-all! root "base")
      (git! root "branch" "-m" "trunk")
      (git! root "update-ref" "refs/remotes/origin/trunk"
            (git-out root "rev-parse" "trunk"))
      (git! root "symbolic-ref" "refs/remotes/origin/HEAD"
            "refs/remotes/origin/trunk")
      (git! root "checkout" "-q" "-b" "feature")
      (write-text! root "feature.txt" "feature\n")
      (commit-all! root "feature")
      (let [change (review-git/capture {:cwd (str root)})]
        (is (= "origin/trunk" (:base change)))
        (is (= ["feature.txt"] (:paths change)))))))

(deftest branch-capture-does-not-check-out-or-include-current-dirt
  (with-temp-repo
    (fn [root]
      (write-text! root "base.txt" "base\n")
      (commit-all! root "base")
      (git! root "checkout" "-q" "-b" "review-branch")
      (write-text! root "reviewed.txt" "review me\n")
      (commit-all! root "review branch")
      (let [review-sha (git-out root "rev-parse" "HEAD")]
        (git! root "checkout" "-q" "main")
        (write-text! root "dirty.txt" "not part of branch review\n")
        (write-text! root "base.txt" "also excluded\n")
        (git! root "add" "base.txt")
        (let [before-status (status root)
              before-index (index-bytes root)
              change (review-git/capture
                      {:cwd (str root)
                       :base "main"
                       :branch "review-branch"})]
          (is (= "branch" (:source change)))
          (is (= ["reviewed.txt"] (:paths change)))
          (is (= "main" (:base change)))
          (is (= "review-branch" (:tip change)))
          (is (= review-sha (:tip-sha change)))
          (is (= "main" (git-out root "branch" "--show-current")))
          (is (= before-status (status root)))
          (is (unchanged-index? before-index (index-bytes root))))))))

(deftest generated-patches-report-renames-binary-and-mode-only-changes
  (with-temp-repo
    (fn [root]
      (write-text! root "old-name.txt" "same contents\n")
      (write-bytes! root "binary.bin" (byte-array [0 1 2 3]))
      (write-text! root "mode.sh" "#!/bin/sh\n")
      (commit-all! root "base")
      (git! root "mv" "old-name.txt" "new-name.txt")
      (write-bytes! root "binary.bin" (byte-array [0 4 5 6]))
      (.setExecutable (.toFile (path root "mode.sh")) true false)
      (let [{:keys [diff paths]} (review-git/capture {:cwd (str root)})]
        (is (= ["binary.bin" "mode.sh" "new-name.txt" "old-name.txt"]
               paths))
        (is (str/includes? diff "rename from old-name.txt"))
        (is (str/includes? diff "rename to new-name.txt"))
        (is (str/includes? diff "Binary files a/binary.bin and b/binary.bin differ"))
        (is (str/includes? diff "old mode 100644"))
        (is (str/includes? diff "new mode 100755"))))))

(deftest generated-patches-handle-ordinary-and-c-quoted-filenames
  (with-temp-repo
    (fn [root]
      (doseq [name ["space name.txt" "café.txt" "tab\tname.txt"]]
        (write-text! root name "before\n"))
      (commit-all! root "base")
      (doseq [name ["space name.txt" "café.txt" "tab\tname.txt"]]
        (write-text! root name "after\n"))
      (is (= ["café.txt" "space name.txt" "tab\tname.txt"]
             (:paths (review-git/capture {:cwd (str root)})))))))

(deftest hunk-content-that-resembles-file-headers-cannot-invent-paths
  (let [patch (str "diff --git a/safe.txt b/safe.txt\n"
                   "index 1111111..2222222 100644\n"
                   "--- a/safe.txt\n"
                   "+++ b/safe.txt\n"
                   "@@ -1 +1 @@\n"
                   "--- a/invented-by-hunk.txt\n"
                   "+++ b/also-invented-by-hunk.txt\n")]
    (is (= ["safe.txt"] (:paths (review-git/capture {:git patch}))))))

(deftest literal-patch-boundary-handles-empty-malformed-conflicting-and-bounded-input
  (testing "empty literal patches are structured no-change captures"
    (let [change (review-git/capture {:git "" :cwd "."})]
      (is (= [] (:paths change)))
      (is (= "git" (:source change)))
      (is (= "" (:diff change)))))
  (testing "command text is not mistaken for patch content"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not standard Git diff output"
         (review-git/capture {:git "git diff --stat"}))))
  (testing "a header without patch metadata is malformed"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"no recognized patch metadata"
         (review-git/capture {:git "diff --git a/a.txt b/a.txt\n"}))))
  (testing "literal input conflicts with repository revision selection"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflicts"
         (review-git/capture {:git "" :base "main"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"conflicts"
         (review-git/capture {:git "" :branch "topic"}))))
  (testing "literal content is checked against the byte cap before parsing"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"exceeds --max-bytes"
         (review-git/capture {:git (str/join (repeat 20 "é"))
                              :max-bytes 20}))))
  (testing "the byte cap must be positive"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"positive integer"
         (review-git/capture {:git "" :max-bytes 0}))))
  (testing "literal patch content is never shell input"
    (with-temp-repo
      (fn [root]
        (let [filename "$(touch literal-ran)"
              patch (str "diff --git a/" filename " b/" filename "\n"
                         "old mode 100644\n"
                         "new mode 100755\n")]
          (is (= [filename]
                 (:paths (review-git/capture {:cwd (str root) :git patch}))))
          (is (not (Files/exists (path root "literal-ran")
                                 (make-array LinkOption 0)))))))))

(deftest generated-output-cap-covers-tracked-and-untracked-content
  (testing "tracked output"
    (with-temp-repo
      (fn [root]
        (write-text! root "large.txt" "small\n")
        (commit-all! root "base")
        (write-text! root "large.txt" (str/join (repeat 4096 "x")))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"exceeds --max-bytes"
             (review-git/capture {:cwd (str root) :max-bytes 256}))))))
  (testing "untracked aggregation cannot bypass the same cap"
    (with-temp-repo
      (fn [root]
        (write-text! root "base.txt" "base\n")
        (commit-all! root "base")
        (write-text! root "large-untracked.txt" (str/join (repeat 4096 "x")))
        (let [error (try
                      (review-git/capture {:cwd (str root) :max-bytes 256})
                      nil
                      (catch clojure.lang.ExceptionInfo exception
                        exception))]
          (is (some? error))
          (is (re-find #"exceeds --max-bytes" (ex-message error)))
          (is (= 256 (:max-bytes (ex-data error))))
          (is (= "large-untracked.txt" (:path (ex-data error)))))))))

(deftest generated-diff-settings-and-special-untracked-files-are-stable
  (with-temp-repo
    (fn [root]
      (write-text! root "tracked.txt" "before\n")
      (commit-all! root "base")
      (let [marker (path root "external-diff-ran")
            external (write-text!
                      root
                      "external-diff.sh"
                      (str "#!/bin/sh\n: > " marker "\nexit 99\n"))]
        (.setExecutable (.toFile external) true false)
        (write-text! root ".gitattributes" "*.txt diff=custom\n")
        (commit-all! root "external diff fixture")
        (git! root "config" "diff.external" (str external))
        (git! root "config" "diff.custom.textconv" (str external))
        (git! root "config" "color.ui" "always")
        (git! root "config" "diff.noprefix" "true")
        (write-text! root "tracked.txt" "after\n")
        (write-text! root "empty.txt" "")
        (write-bytes! root "binary-untracked.bin" (byte-array [0 1 2]))
        (Files/createSymbolicLink
         (path root "external-link")
         (Path/of "/definitely/not/read-by-review-capture"
                  (make-array String 0))
         (make-array FileAttribute 0))
        (let [{:keys [diff paths]} (review-git/capture {:cwd (str root)})]
          (is (= ["binary-untracked.bin"
                  "empty.txt"
                  "external-link"
                  "tracked.txt"]
                 paths))
          (is (str/includes? diff "diff --git a/tracked.txt b/tracked.txt"))
          (is (str/includes? diff "Binary files /dev/null and b/binary-untracked.bin differ"))
          (is (str/includes? diff "new file mode 100644"))
          (is (str/includes? diff "new file mode 120000"))
          (is (not (str/includes? diff "\u001b[")))
          (is (not (Files/exists marker (make-array LinkOption 0)))))))))

(deftest revision-option-injection-is-refused-before-running-git
  (with-temp-repo
    (fn [root]
      (write-text! root "base.txt" "base\n")
      (commit-all! root "base")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"--base requires a revision"
           (review-git/capture {:cwd (str root) :base "--all"})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"--branch requires a revision"
           (review-git/capture {:cwd (str root)
                                :base "main"
                                :branch "--all"})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unable to resolve --base revision"
           (review-git/capture {:cwd (str root) :base "missing-base"})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unable to resolve --branch revision"
           (review-git/capture {:cwd (str root)
                                :base "main"
                                :branch "missing-branch"})))
      (git! root "symbolic-ref" "HEAD" "refs/heads/unborn")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unable to resolve HEAD revision"
           (review-git/capture {:cwd (str root) :base "main"}))))))

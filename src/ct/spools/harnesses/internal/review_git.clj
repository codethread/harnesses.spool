(ns ct.spools.harnesses.internal.review-git
  "Immutable, bounded Git change capture for declarative reviews."
  (:require [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]
           [java.nio.file LinkOption Path]))

(def ^:private default-max-bytes (* 512 1024))
(def ^:private metadata-max-bytes (* 64 1024))
(def ^:private stderr-max-bytes (* 64 1024))

(def ^:private stable-diff-options
  ["--no-ext-diff"
   "--no-textconv"
   "--no-color"
   "--src-prefix=a/"
   "--dst-prefix=b/"])

(declare ^:private capture-repository
         normalize-directory
         parse-paths
         repo-root
         require-within-cap!
         valid-ref!)

(defn capture
  "Capture one bounded, authoritative change from a review request.

  Repository captures use stable Git prefixes and omit binary payloads. Binary
  files are represented by Git's `Binary files ... differ` marker. Literal
  `:git` input is treated only as patch content and is never executed."
  [{:keys [cwd base branch git max-bytes] :as request}]
  (let [max-bytes (or max-bytes default-max-bytes)
        literal? (some? git)]
    (when-not (and (integer? max-bytes) (pos? max-bytes))
      (fail! "--max-bytes must be a positive integer" {:max-bytes max-bytes}))
    (when (and literal? (or (some? base) (some? branch)))
      (fail! "--git conflicts with --base and --branch" {}))
    (when (and literal? (not (string? git)))
      (fail! "--git requires literal patch content" {:git git}))
    (when (some? base)
      (valid-ref! base "--base"))
    (when (some? branch)
      (valid-ref! branch "--branch"))
    (if literal?
      (do
        (require-within-cap! git max-bytes {})
        {:source "git"
         :repo-root (normalize-directory (or cwd (System/getProperty "user.dir")))
         :base nil
         :base-sha nil
         :merge-base nil
         :tip nil
         :tip-sha nil
         :diff git
         :paths (parse-paths git)})
      (let [root (repo-root (or cwd (System/getProperty "user.dir")))]
        (capture-repository root request max-bytes)))))

(defn- normalize-directory [cwd]
  (-> (Path/of (str cwd) (make-array String 0))
      .toAbsolutePath
      .normalize
      str))

(defn- utf8-byte-count-over?
  [^String text max-bytes]
  (loop [index 0
         total 0]
    (if (= index (.length text))
      false
      (let [code-point (.codePointAt text index)
            width (cond
                    (<= code-point 0x7f) 1
                    (<= code-point 0x7ff) 2
                    (<= code-point 0xffff) 3
                    :else 4)
            next-total (+ total width)]
        (if (> next-total max-bytes)
          true
          (recur (+ index (Character/charCount code-point)) next-total))))))

(defn- require-within-cap! [text max-bytes context]
  (when (utf8-byte-count-over? text max-bytes)
    (fail! "Review diff exceeds --max-bytes; narrow the change or raise the cap"
           (merge {:max-bytes max-bytes} context))))

(defn- bytes->string [^ByteArrayOutputStream output]
  (String. (.toByteArray output) StandardCharsets/UTF_8))

(defn- read-bounded
  [^InputStream stream max-bytes overflow!]
  (let [output (ByteArrayOutputStream.)
        buffer (byte-array 8192)]
    (loop [total 0]
      (let [read-limit (int (min (alength buffer) (inc (- max-bytes total))))
            read-count (.read stream buffer 0 read-limit)]
        (cond
          (= -1 read-count)
          {:text (bytes->string output) :bytes total}

          (> (+ total read-count) max-bytes)
          (do
            (overflow!)
            {:text (bytes->string output) :bytes total :overflow? true})

          :else
          (do
            (.write output buffer 0 read-count)
            (recur (+ total read-count))))))))

(defn- invoke-git [cwd max-out args]
  (let [command (into ["git"
                       "-c" "diff.external="
                       "-c" "core.pager=cat"
                       "-c" "color.ui=false"
                       "-c" "core.quotePath=true"]
                      args)
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (.toFile (Path/of cwd (make-array String 0)))))
        _ (.put (.environment builder) "LC_ALL" "C")
        process (.start builder)
        stop! #(.destroyForcibly process)
        stdout (future (read-bounded (.getInputStream process) max-out stop!))
        stderr (future (read-bounded (.getErrorStream process)
                                     stderr-max-bytes
                                     stop!))
        exit (.waitFor process)]
    {:command command
     :exit exit
     :stdout @stdout
     :stderr @stderr}))

(defn- run-git
  [cwd accepted-exits max-out context & args]
  (let [{:keys [command exit stdout stderr]} (invoke-git cwd max-out args)
        details (merge context
                       {:command command
                        :exit exit
                        :stderr (:text stderr)})]
    (when (:overflow? stdout)
      (fail! "Review diff exceeds --max-bytes; narrow the change or raise the cap"
             (cond-> (assoc details :capture-bound max-out)
               (not (contains? details :max-bytes))
               (assoc :max-bytes max-out))))
    (when (:overflow? stderr)
      (fail! "Git stderr exceeded the capture safety bound"
             (assoc details :stderr-max-bytes stderr-max-bytes)))
    (when-not (contains? accepted-exits exit)
      (fail! "Git change capture failed"
             (assoc details :stdout (:text stdout))))
    (:text stdout)))

(defn- repo-root [cwd]
  (let [cwd (normalize-directory cwd)
        root (str/trim
              (run-git cwd #{0} metadata-max-bytes {:cwd cwd}
                       "rev-parse" "--show-toplevel"))]
    (str (.toRealPath (Path/of root (make-array String 0))
                      (make-array LinkOption 0)))))

(defn- valid-ref! [ref option]
  (when-not (string? ref)
    (fail! (str option " requires a revision string") {:ref ref}))
  (when (or (str/blank? ref) (str/starts-with? ref "-"))
    (fail! (str option " requires a revision that does not begin with '-'")
           {:ref ref}))
  ref)

(defn- resolve-ref [root ref]
  (let [output (run-git root #{0 1 128} metadata-max-bytes {:ref ref}
                        "rev-parse" "--verify" "--quiet" "--end-of-options"
                        (str ref "^{commit}"))]
    (not-empty (str/trim output))))

(defn- resolve-ref! [root ref option]
  (or (resolve-ref root (valid-ref! ref option))
      (fail! (str "Unable to resolve " option " revision to a commit")
             {:option option :ref ref :repo-root root})))

(defn- default-base [root]
  (let [remote-default (not-empty
                        (str/trim
                         (run-git root #{0 1} metadata-max-bytes {}
                                  "symbolic-ref" "--quiet" "--short"
                                  "refs/remotes/origin/HEAD")))
        candidates (distinct
                    (remove nil?
                            [remote-default
                             "origin/main"
                             "origin/master"
                             "main"
                             "master"]))]
    (or (some #(when (resolve-ref root %) %) candidates)
        (fail! "No sensible default review base; pass --base <ref>"
               {:repo-root root :candidates (vec candidates)}))))

(defn- merge-base! [root base-ref base-sha tip-ref tip-sha]
  (let [common (str/trim
                (run-git root #{0 1} metadata-max-bytes
                         {:base base-ref :tip tip-ref}
                         "merge-base" "--" base-sha tip-sha))]
    (or (not-empty common)
        (fail! "Review base and tip have no common commit"
               {:base base-ref
                :base-sha base-sha
                :tip tip-ref
                :tip-sha tip-sha
                :repo-root root}))))

(defn- untracked-paths [root max-bytes]
  (->> (str/split (run-git root #{0} max-bytes {:max-bytes max-bytes}
                           "ls-files" "--others" "--exclude-standard" "-z")
                  #"\u0000")
       (remove str/blank?)
       sort
       vec))

(defn- tracked-diff [root common tip-sha branch? max-bytes]
  (let [revisions (if branch? [common tip-sha] [common])]
    (apply run-git root #{0} max-bytes {:max-bytes max-bytes}
           "diff" (concat stable-diff-options
                          ["--find-renames"]
                          revisions
                          ["--"]))))

(defn- untracked-diff [root path remaining-bytes max-bytes]
  (apply run-git root #{0 1} remaining-bytes
         {:max-bytes max-bytes
          :remaining-bytes remaining-bytes
          :path path}
         "diff" (concat stable-diff-options
                        ["--no-index" "--" "/dev/null" path])))

(defn- capture-repository
  [root {:keys [base branch]} max-bytes]
  (let [base-ref (valid-ref! (or base (default-base root)) "--base")
        tip-ref (valid-ref! (or branch "HEAD") "--branch")
        base-sha (resolve-ref! root base-ref "--base")
        tip-sha (resolve-ref! root tip-ref (if branch "--branch" "HEAD"))
        common (merge-base! root base-ref base-sha tip-ref tip-sha)
        branch? (some? branch)
        tracked (tracked-diff root common tip-sha branch? max-bytes)
        tracked-bytes (alength (.getBytes ^String tracked StandardCharsets/UTF_8))
        extras (if branch? [] (untracked-paths root max-bytes))
        {:keys [parts]}
        (reduce (fn [{:keys [parts bytes]} path]
                  (let [remaining (- max-bytes bytes)
                        patch (untracked-diff root path remaining max-bytes)
                        patch-bytes (alength
                                     (.getBytes ^String patch
                                                StandardCharsets/UTF_8))]
                    {:parts (conj parts patch)
                     :bytes (+ bytes patch-bytes)}))
                {:parts [tracked] :bytes tracked-bytes}
                extras)
        diff (str/join parts)]
    {:source (if branch? "branch" "working-tree")
     :repo-root root
     :base base-ref
     :base-sha base-sha
     :merge-base common
     :tip tip-ref
     :tip-sha tip-sha
     :paths (parse-paths diff)
     :diff diff}))

(defn- decode-git-bytes [^ByteArrayOutputStream output token]
  (try
    (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (str (.decode decoder (ByteBuffer/wrap (.toByteArray output)))))
    (catch CharacterCodingException _
      (fail! "Git diff contains an invalid UTF-8 quoted filename"
             {:token token}))))

(def ^:private c-escapes
  {\a 7, \b 8, \t 9, \n 10, \v 11, \f 12, \r 13, \\ 92, \" 34})

(defn- write-code-point! [^ByteArrayOutputStream output code-point]
  (let [text (String. (Character/toChars code-point))
        bytes (.getBytes text StandardCharsets/UTF_8)]
    (.write output bytes 0 (alength bytes))))

(defn- parse-quoted-token [text start]
  (let [output (ByteArrayOutputStream.)]
    (loop [index (inc start)]
      (when (>= index (count text))
        (fail! "Unterminated quoted filename in Git diff" {:header text}))
      (let [character (.charAt ^String text index)]
        (cond
          (= character \")
          [(decode-git-bytes output (subs text start (inc index))) (inc index)]

          (= character \\)
          (let [escape-index (inc index)]
            (when (>= escape-index (count text))
              (fail! "Incomplete filename escape in Git diff" {:header text}))
            (let [escaped (.charAt ^String text escape-index)]
              (if (<= (int \0) (int escaped) (int \7))
                (let [end (loop [cursor escape-index
                                 digits 0]
                            (if (and (< cursor (count text))
                                     (< digits 3)
                                     (<= (int \0)
                                         (int (.charAt ^String text cursor))
                                         (int \7)))
                              (recur (inc cursor) (inc digits))
                              cursor))
                      value (Integer/parseInt (subs text escape-index end) 8)]
                  (when (> value 255)
                    (fail! "Out-of-range octal filename escape in Git diff"
                           {:header text :escape (subs text escape-index end)}))
                  (.write output value)
                  (recur end))
                (if-let [value (get c-escapes escaped)]
                  (do (.write output (int value))
                      (recur (+ index 2)))
                  (fail! "Unsupported filename escape in Git diff"
                         {:header text :escape (str escaped)})))))

          :else
          (let [code-point (.codePointAt ^String text index)]
            (write-code-point! output code-point)
            (recur (+ index (Character/charCount code-point)))))))))

(defn- parse-second-token [body start]
  (if (= \" (.charAt ^String body start))
    (let [[token end] (parse-quoted-token body start)]
      (when-not (= end (count body))
        (fail! "Unexpected content after quoted filename in Git diff"
               {:header body}))
      token)
    (subs body start)))

(defn- unquoted-separators [body]
  (keep (fn [index]
          (when (and (= \space (.charAt ^String body index))
                     (or (str/starts-with? (subs body (inc index)) "b/")
                         (str/starts-with? (subs body (inc index)) "\"b/")))
            index))
        (range (count body))))

(defn- parse-diff-header [line]
  (let [body (subs line (count "diff --git "))]
    (if (str/starts-with? body "\"")
      (let [[old end] (parse-quoted-token body 0)]
        (when (or (>= end (count body))
                  (not= \space (.charAt ^String body end)))
          (fail! "Malformed quoted Git diff header" {:header line}))
        [old (parse-second-token body (inc end))])
      (let [separators (vec (unquoted-separators body))]
        (when-not (= 1 (count separators))
          (fail! "Ambiguous or malformed Git diff paths"
                 {:header line :separator-count (count separators)}))
        (let [separator (first separators)]
          [(subs body 0 separator)
           (parse-second-token body (inc separator))])))))

(defn- repo-path [path prefix header]
  (when-not (str/starts-with? path prefix)
    (fail! "Git diff header lacks stable a/ and b/ path prefixes"
           {:header header :path path :expected-prefix prefix}))
  (let [relative (subs path (count prefix))
        components (str/split relative #"/" -1)]
    (when (or (str/blank? relative)
              (str/starts-with? relative "/")
              (some #{"" "." ".."} components)
              (str/includes? relative "\u0000"))
      (fail! "Git diff contains an invalid repository-relative path"
             {:header header :path path}))
    relative))

(defn- patch-metadata? [line]
  (or (re-matches #"index [0-9a-f]+\.\.[0-9a-f]+(?: [0-7]{6})?" line)
      (re-matches #"(?:old|new|deleted file|new file) mode [0-7]{6}" line)
      (re-matches #"(?:dis)?similarity index [0-9]+%" line)
      (str/starts-with? line "rename from ")
      (str/starts-with? line "rename to ")
      (str/starts-with? line "copy from ")
      (str/starts-with? line "copy to ")
      (str/starts-with? line "Binary files ")
      (= line "GIT binary patch")
      (str/starts-with? line "--- ")
      (str/starts-with? line "+++ ")))

(defn- parse-sections [diff]
  (loop [lines (str/split-lines diff)
         current nil
         sections []]
    (if-let [line (first lines)]
      (if (str/starts-with? line "diff --git ")
        (recur (next lines)
               {:header line :body []}
               (cond-> sections current (conj current)))
        (do
          (when (and (nil? current) (not (str/blank? line)))
            (fail! "Literal --git input is not standard Git diff output"
                   {:line line}))
          (recur (next lines)
                 (when current (update current :body conj line))
                 sections)))
      (cond-> sections current (conj current)))))

(defn- paired-metadata! [header prelude old-prefix new-prefix description]
  (let [old? (some #(str/starts-with? % old-prefix) prelude)
        new? (some #(str/starts-with? % new-prefix) prelude)]
    (when (not= (boolean old?) (boolean new?))
      (fail! (str "Git diff section has incomplete " description " metadata")
             {:header header}))))

(defn- validate-section! [header body]
  (let [prelude (take-while #(not (str/starts-with? % "@@")) body)]
    (when-not (some patch-metadata? prelude)
      (fail! "Git diff section has no recognized patch metadata"
             {:header header}))
    (paired-metadata! header prelude "--- " "+++ " "file header")
    (paired-metadata! header prelude "rename from " "rename to " "rename")
    (paired-metadata! header prelude "copy from " "copy to " "copy")))

(defn- parse-paths [diff]
  (if (str/blank? diff)
    []
    (let [sections (parse-sections diff)]
      (when (empty? sections)
        (fail! "Literal --git input is not standard Git diff output" {}))
      (->> sections
           (mapcat (fn [{:keys [header body]}]
                     (validate-section! header body)
                     (let [[old new] (parse-diff-header header)]
                       [(repo-path old "a/" header)
                        (repo-path new "b/" header)])))
           distinct
           sort
           vec))))

(ns ct.spools.harnesses.internal.guidance-prompt-controls
  "Provider argv ownership checks for native managed guidance."
  (:require [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [fail!]]))

(def ^:private codex-prompt-config-keys
  #{"developer_instructions" "instructions" "model_instructions_file"})

(defn- skip-space [^String value index]
  (loop [index index]
    (if (and (< index (.length value))
             (contains? #{\space \tab \newline \return}
                        (.charAt value index)))
      (recur (inc index))
      index)))

(defn- hex-value [character]
  (cond
    (<= (int \0) (int character) (int \9)) (- (int character) (int \0))
    (<= (int \A) (int character) (int \F)) (+ 10 (- (int character) (int \A)))
    (<= (int \a) (int character) (int \f)) (+ 10 (- (int character) (int \a)))
    :else -1))

(defn- escaped-code-point [^String value index digits]
  (when (<= (+ index digits) (.length value))
    (loop [offset 0 result 0]
      (if (= offset digits)
        result
        (let [digit (hex-value (.charAt value (+ index offset)))]
          (when-not (neg? digit)
            (recur (inc offset) (+ (* result 16) digit))))))))

(defn- basic-key-segment [^String value start]
  (let [output (StringBuilder.)]
    (loop [index (inc start)]
      (when (< index (.length value))
        (let [character (.charAt value index)]
          (cond
            (= character \") [(str output) (inc index)]

            (= character \\)
            (let [escape-index (inc index)]
              (when (< escape-index (.length value))
                (let [escaped (.charAt value escape-index)]
                  (case escaped
                    \" (do (.append output \") (recur (+ index 2)))
                    \\ (do (.append output \\) (recur (+ index 2)))
                    \b (do (.append output \backspace) (recur (+ index 2)))
                    \t (do (.append output \tab) (recur (+ index 2)))
                    \n (do (.append output \newline) (recur (+ index 2)))
                    \f (do (.append output \formfeed) (recur (+ index 2)))
                    \r (do (.append output \return) (recur (+ index 2)))
                    \u (when-let [code (escaped-code-point value
                                                           (+ index 2) 4)]
                         (.appendCodePoint output code)
                         (recur (+ index 6)))
                    \U (when-let [code (escaped-code-point value
                                                           (+ index 2) 8)]
                         (when (Character/isValidCodePoint code)
                           (.appendCodePoint output code)
                           (recur (+ index 10))))
                    nil))))

            (< (int character) 32) nil

            :else
            (do
              (.append output character)
              (recur (inc index)))))))))

(defn- literal-key-segment [^String value start]
  (let [end (.indexOf value "'" (inc start))]
    (when (not= -1 end)
      [(.substring value (inc start) end) (inc end)])))

(defn- bare-key-segment [^String value start]
  (loop [index start]
    (if (and (< index (.length value))
             (let [character (.charAt value index)]
               (or (<= (int \a) (int character) (int \z))
                   (<= (int \A) (int character) (int \Z))
                   (<= (int \0) (int character) (int \9))
                   (= \_ character)
                   (= \- character))))
      (recur (inc index))
      (when (< start index)
        [(.substring value start index) index]))))

(defn- key-segment [^String value index]
  (when (< index (.length value))
    (case (.charAt value index)
      \" (basic-key-segment value index)
      \' (literal-key-segment value index)
      (bare-key-segment value index))))

(defn- key-assignment [^String value start]
  (loop [index (skip-space value start)
         segments []]
    (when-let [[segment end] (key-segment value index)]
      (let [index (skip-space value end)]
        (when (< index (.length value))
          (case (.charAt value index)
            \. (recur (skip-space value (inc index))
                      (conj segments segment))
            \= [(conj segments segment) (inc index)]
            nil))))))

(defn- codex-prompt-path? [segments]
  (let [target (last segments)]
    (or (and (= 1 (count segments))
             (contains? codex-prompt-config-keys target))
        (and (= 3 (count segments))
             (= "profiles" (first segments))
             (= "model_instructions_file" target)))))

(defn- skip-toml-value [^String value start]
  (loop [index start square-depth 0 curly-depth 0 quote nil escaped? false]
    (if (>= index (.length value))
      index
      (let [character (.charAt value index)]
        (cond
          quote
          (cond
            (and (= quote \") escaped?)
            (recur (inc index) square-depth curly-depth quote false)

            (and (= quote \") (= character \\))
            (recur (inc index) square-depth curly-depth quote true)

            (= quote character)
            (recur (inc index) square-depth curly-depth nil false)

            :else
            (recur (inc index) square-depth curly-depth quote false))

          (contains? #{\" \'} character)
          (recur (inc index) square-depth curly-depth character false)

          (= \[ character)
          (recur (inc index) (inc square-depth) curly-depth nil false)

          (and (= \] character) (pos? square-depth))
          (recur (inc index) (dec square-depth) curly-depth nil false)

          (= \{ character)
          (recur (inc index) square-depth (inc curly-depth) nil false)

          (and (= \} character) (pos? curly-depth))
          (recur (inc index) square-depth (dec curly-depth) nil false)

          (and (zero? square-depth) (zero? curly-depth)
               (contains? #{\, \}} character))
          index

          :else
          (recur (inc index) square-depth curly-depth nil false))))))

(declare inline-table-prompt)

(defn- continue-inline-table [value index prefix]
  (let [index (skip-space value index)]
    (when (< index (.length ^String value))
      (case (.charAt ^String value index)
        \, (inline-table-prompt value (inc index) prefix)
        \} [false (inc index)]
        nil))))

(defn- inline-table-prompt [^String value start prefix]
  (let [index (skip-space value start)]
    (cond
      (>= index (.length value)) nil
      (= \} (.charAt value index)) [false (inc index)]
      :else
      (when-let [[local-path value-start] (key-assignment value index)]
        (let [path (into (vec prefix) local-path)
              value-start (skip-space value value-start)]
          (cond
            (codex-prompt-path? path) [true value-start]

            (and (< value-start (.length value))
                 (= \{ (.charAt value value-start)))
            (when-let [[found? end]
                       (inline-table-prompt value (inc value-start) path)]
              (if found?
                [true end]
                (continue-inline-table value end prefix)))

            :else
            (continue-inline-table value
                                   (skip-toml-value value value-start)
                                   prefix)))))))

(defn- codex-prompt-assignment? [assignment]
  (when (string? assignment)
    (when-let [[path value-start] (key-assignment assignment 0)]
      (or (codex-prompt-path? path)
          (let [value-start (skip-space assignment value-start)]
            (and (< value-start (.length ^String assignment))
                 (= \{ (.charAt ^String assignment value-start))
                 (true? (first (inline-table-prompt
                                assignment (inc value-start) path)))))))))

(defn- attached-config [argument]
  (cond
    (str/starts-with? argument "--config=")
    (subs argument (count "--config="))

    (str/starts-with? argument "-c=")
    (subs argument (count "-c="))

    (and (str/starts-with? argument "-c")
         (< 2 (count argument)))
    (subs argument 2)

    :else nil))

(defn- codex-prompt-control [argv]
  (loop [remaining argv]
    (when-let [argument (first remaining)]
      (cond
        (= "--" argument) nil

        (contains? #{"-c" "--config"} argument)
        (let [assignment (second remaining)]
          (when (nil? assignment)
            (fail! "Codex prompt configuration flag is missing its value"
                   {:argument argument}))
          (if (codex-prompt-assignment? assignment)
            [argument assignment]
            (recur (nnext remaining))))

        :else
        (if-let [assignment (attached-config argument)]
          (if (codex-prompt-assignment? assignment)
            [argument]
            (recur (next remaining)))
          (recur (next remaining)))))))

;; Keep this consumption order aligned with Pi v0.84.4 `parseArgs`:
;; packages/coding-agent/src/cli/args.ts.
(def ^:private pi-prompt-options
  #{"--system-prompt" "--append-system-prompt"})

(def ^:private pi-unconditional-value-options
  #{"--mode" "--provider" "--model" "--api-key" "--name" "-n"
    "--session" "--session-id" "--fork" "--session-dir" "--models"
    "--tools" "-t" "--exclude-tools" "-xt" "--thinking" "--export"
    "--extension" "-e" "--skill" "--prompt-template" "--theme"})

(def ^:private pi-conditional-value-options #{"--use-theme" "--tui-mode"})

(defn- pi-prompt-equal-option? [argument]
  (or (str/starts-with? argument "--system-prompt=")
      (str/starts-with? argument "--append-system-prompt=")))

(defn- starts-with-token? [prefix value]
  (and (string? value) (str/starts-with? value prefix)))

(defn- pi-consumes-next? [argument next-argument]
  (cond
    (nil? next-argument) false

    (contains? pi-unconditional-value-options argument) true

    (contains? pi-conditional-value-options argument)
    (not (starts-with-token? "-" next-argument))

    (= "--list-models" argument)
    (and (not (starts-with-token? "-" next-argument))
         (not (starts-with-token? "@" next-argument)))

    (contains? #{"--print" "-p"} argument)
    (and (not (starts-with-token? "@" next-argument))
         (or (not (starts-with-token? "-" next-argument))
             (starts-with-token? "---" next-argument)))

    (and (str/starts-with? argument "--")
         (not (str/includes? argument "=")))
    (and (not (starts-with-token? "-" next-argument))
         (not (starts-with-token? "@" next-argument)))

    :else false))

(defn- pi-prompt-control [argv]
  (loop [remaining argv]
    (when-let [argument (first remaining)]
      (cond
        (= "--" argument) nil
        (or (contains? pi-prompt-options argument)
            (pi-prompt-equal-option? argument)) argument
        (str/includes? argument "=") (recur (next remaining))
        (pi-consumes-next? argument (second remaining))
        (recur (nnext remaining))
        :else (recur (next remaining))))))

(defn reject!
  "Reject raw prompt controls competing with the selected native owner."
  [harness argv]
  (when-let [control (case harness
                       "codex" (codex-prompt-control argv)
                       "pi" (pi-prompt-control argv)
                       nil)]
    (fail!
     (str "Native guidance rejects raw provider prompt controls. "
          "Use the wrapper-level --append-system-prompt option, or explicitly "
          "select --guidance-transport legacy.")
     {:harness harness :competing-argv control})))

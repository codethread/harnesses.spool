(ns ct.spools.harnesses.internal.guidance-prompt-controls
  "Provider argv ownership checks for native managed guidance."
  (:require [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [fail!]]))

(defn- config-key [assignment]
  (when-let [key-text (some-> assignment (str/split #"=" 2) first str/trim)]
    (if (and (<= 2 (count key-text))
             (or (and (str/starts-with? key-text "\"")
                      (str/ends-with? key-text "\""))
                 (and (str/starts-with? key-text "'")
                      (str/ends-with? key-text "'"))))
      (subs key-text 1 (dec (count key-text)))
      key-text)))

(def ^:private codex-prompt-config-keys
  #{"developer_instructions" "base_instructions" "instructions_file"
    "model_instructions_file"})

(defn- prompt-key? [key]
  (let [key (some-> key str/lower-case)]
    (or (contains? codex-prompt-config-keys key)
        (some->> key
                 (re-find #"(?:instruction|system[_-]?prompt)")
                 boolean))))

(defn- prompt-option? [argument]
  (and (string? argument)
       (str/starts-with? argument "-")
       (some? (re-find #"(?:instruction|system[_-]?prompt)"
                       (str/lower-case argument)))))

(defn- codex-prompt-control [argv]
  (loop [remaining argv]
    (when-let [argument (first remaining)]
      (cond
        (#{"-c" "--config"} argument)
        (let [assignment (second remaining)]
          (when (nil? assignment)
            (fail! "Codex prompt configuration flag is missing its value"
                   {:argument argument}))
          (or (when (prompt-key? (config-key assignment))
                [argument assignment])
              (recur (nnext remaining))))

        (or (str/starts-with? argument "--config=")
            (str/starts-with? argument "-c="))
        (or (when (prompt-key? (config-key
                                (subs argument
                                      (inc (str/index-of argument "=")))))
              [argument])
            (recur (next remaining)))

        (and (str/starts-with? argument "-c")
             (< 2 (count argument)))
        (or (when (prompt-key? (config-key (subs argument 2)))
              [argument])
            (recur (next remaining)))

        (prompt-option? argument) [argument]

        :else (recur (next remaining))))))

(defn- pi-prompt-control [argv]
  (some #(when (prompt-option? %) %) argv))

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

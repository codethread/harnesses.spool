(ns ct.spools.harnesses.internal.strict-json
  "Strict bounded JSON parsing and RFC 8785 canonicalization."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn utf8-bytes
  "Return the UTF-8 byte count of `value`."
  [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- skip-space [^String source index]
  (loop [index index]
    (if (and (< index (.length source))
             (contains? #{\space \tab \newline \return}
                        (.charAt source index)))
      (recur (inc index))
      index)))

(defn- hex-value [character]
  (cond
    (<= (int \0) (int character) (int \9)) (- (int character) (int \0))
    (<= (int \A) (int character) (int \F)) (+ 10 (- (int character) (int \A)))
    (<= (int \a) (int character) (int \f)) (+ 10 (- (int character) (int \a)))
    :else -1))

(defn- read-hex [^String source index]
  (when (> (+ index 4) (.length source))
    (fail! "JSON string has an incomplete Unicode escape" {}))
  (loop [offset 0 value 0]
    (if (= 4 offset)
      value
      (let [digit (hex-value (.charAt source (+ index offset)))]
        (when (neg? digit)
          (fail! "JSON string has an invalid Unicode escape" {}))
        (recur (inc offset) (+ (* value 16) digit))))))

(defn- parse-string [^String source start]
  (let [output (StringBuilder.)]
    (loop [index (inc start)]
      (when (>= index (.length source))
        (fail! "JSON string is unterminated" {}))
      (let [character (.charAt source index)]
        (cond
          (= character \") [(str output) (inc index)]

          (= character \\)
          (let [escape-index (inc index)]
            (when (>= escape-index (.length source))
              (fail! "JSON string has an incomplete escape" {}))
            (let [escaped (.charAt source escape-index)]
              (case escaped
                \" (do (.append output \") (recur (+ index 2)))
                \\ (do (.append output \\) (recur (+ index 2)))
                \/ (do (.append output \/) (recur (+ index 2)))
                \b (do (.append output \backspace) (recur (+ index 2)))
                \f (do (.append output \formfeed) (recur (+ index 2)))
                \n (do (.append output \newline) (recur (+ index 2)))
                \r (do (.append output \return) (recur (+ index 2)))
                \t (do (.append output \tab) (recur (+ index 2)))
                \u
                (let [code (read-hex source (+ index 2))
                      character (char code)]
                  (cond
                    (Character/isHighSurrogate character)
                    (let [next-index (+ index 6)]
                      (when-not (and (<= (+ next-index 6) (.length source))
                                     (= \\ (.charAt source next-index))
                                     (= \u (.charAt source (inc next-index))))
                        (fail! "JSON string has an unpaired high surrogate" {}))
                      (let [low (char (read-hex source (+ next-index 2)))]
                        (when-not (Character/isLowSurrogate low)
                          (fail! "JSON string has an unpaired high surrogate" {}))
                        (.append output character)
                        (.append output low)
                        (recur (+ next-index 6))))

                    (Character/isLowSurrogate character)
                    (fail! "JSON string has an unpaired low surrogate" {})

                    :else
                    (do (.append output character)
                        (recur (+ index 6)))))
                (fail! "JSON string has an invalid escape" {:escape escaped}))))

          (< (int character) 32)
          (fail! "JSON string contains an unescaped control character" {})

          (Character/isHighSurrogate character)
          (let [next-index (inc index)]
            (when-not (and (< next-index (.length source))
                           (Character/isLowSurrogate
                            (.charAt source next-index)))
              (fail! "JSON string has an unpaired high surrogate" {}))
            (.append output character)
            (.append output (.charAt source next-index))
            (recur (inc next-index)))

          (Character/isLowSurrogate character)
          (fail! "JSON string has an unpaired low surrogate" {})

          :else
          (do (.append output character)
              (recur (inc index))))))))

(declare parse-value canonical-data)

(defn- parse-array [^String source start]
  (loop [index (skip-space source (inc start))
         result []
         value-required? false]
    (when (>= index (.length source))
      (fail! "JSON array is unterminated" {}))
    (if (= \] (.charAt source index))
      (if value-required?
        (fail! "JSON array has a trailing comma" {})
        [result (inc index)])
      (let [[value next-index] (parse-value source index)
            delimiter-index (skip-space source next-index)]
        (when (>= delimiter-index (.length source))
          (fail! "JSON array is unterminated" {}))
        (case (.charAt source delimiter-index)
          \, (recur (skip-space source (inc delimiter-index))
                    (conj result value)
                    true)
          \] [(conj result value) (inc delimiter-index)]
          (fail! "JSON array has an invalid delimiter" {}))))))

(defn- parse-object [^String source start]
  (loop [index (skip-space source (inc start))
         result {}
         member-required? false]
    (when (>= index (.length source))
      (fail! "JSON object is unterminated" {}))
    (if (= \} (.charAt source index))
      (if member-required?
        (fail! "JSON object has a trailing comma" {})
        [result (inc index)])
      (do
        (when-not (= \" (.charAt source index))
          (fail! "JSON object keys must be strings" {}))
        (let [[key key-end] (parse-string source index)
              colon-index (skip-space source key-end)]
          (when (contains? result key)
            (fail! "JSON object contains a duplicate key" {:key key}))
          (when-not (and (< colon-index (.length source))
                         (= \: (.charAt source colon-index)))
            (fail! "JSON object key is missing its colon" {:key key}))
          (let [[value value-end]
                (parse-value source (skip-space source (inc colon-index)))
                delimiter-index (skip-space source value-end)
                next-result (assoc result key value)]
            (when (>= delimiter-index (.length source))
              (fail! "JSON object is unterminated" {}))
            (case (.charAt source delimiter-index)
              \, (recur (skip-space source (inc delimiter-index))
                        next-result
                        true)
              \} [next-result (inc delimiter-index)]
              (fail! "JSON object has an invalid delimiter" {:key key}))))))))

(def ^:private number-pattern
  #"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
(def ^:private maximum-safe-integer 9007199254740991)

(defn- parse-number [^String source start]
  (let [matcher (re-matcher number-pattern source)]
    (.region matcher start (.length source))
    (when-not (.lookingAt matcher)
      (fail! "JSON number is malformed" {}))
    (let [token (.group matcher)
          value (json/read-str token :bigdec true)]
      (when-not (and (integer? value)
                     (<= (- maximum-safe-integer)
                         value maximum-safe-integer))
        (fail! "Protocol JSON numbers must be safe integral values"
               {:value token}))
      [value (.end matcher)])))

(defn- parse-literal [^String source start literal value]
  (let [end (+ start (count literal))]
    (when-not (and (<= end (.length source))
                   (= literal (subs source start end)))
      (fail! "JSON literal is malformed" {}))
    [value end]))

(defn- parse-value [^String source start]
  (let [index (skip-space source start)]
    (when (>= index (.length source))
      (fail! "JSON value is missing" {}))
    (case (.charAt source index)
      \{ (parse-object source index)
      \[ (parse-array source index)
      \" (parse-string source index)
      \t (parse-literal source index "true" true)
      \f (parse-literal source index "false" false)
      \n (parse-literal source index "null" nil)
      (parse-number source index))))

(defn parse-object!
  "Parse one bounded JSON object with duplicate-key and excess-data rejection."
  [source max-bytes label]
  (when-not (string? source)
    (fail! (str label " must be JSON text") {:value source}))
  (when (> (utf8-bytes source) max-bytes)
    (fail! (str label " exceeds its UTF-8 byte limit")
           {:max-bytes max-bytes :actual-bytes (utf8-bytes source)}))
  (let [[value end] (parse-value source 0)
        final-index (skip-space source end)]
    (when-not (= final-index (.length ^String source))
      (fail! (str label " must contain exactly one JSON value") {}))
    (when-not (map? value)
      (fail! (str label " must be a JSON object") {:value value}))
    value))

(defn- valid-unicode? [^String value]
  (loop [index 0]
    (if (= index (.length value))
      true
      (let [character (.charAt value index)]
        (cond
          (Character/isHighSurrogate character)
          (and (< (inc index) (.length value))
               (Character/isLowSurrogate (.charAt value (inc index)))
               (recur (+ index 2)))

          (Character/isLowSurrogate character) false
          :else (recur (inc index)))))))

(defn- normalize-string [value]
  (when-not (valid-unicode? value)
    (fail! "Canonical protocol JSON contains malformed Unicode text" {}))
  value)

(defn- normalize-map [value]
  (reduce-kv
   (fn [result key item]
     (let [key (cond
                 (string? key) key
                 (keyword? key) (name key)
                 :else (fail! "Canonical JSON object keys must be strings"
                              {:key key}))]
       (when (contains? result key)
         (fail! "Canonical JSON object has colliding keys" {:key key}))
       (assoc result key (canonical-data item))))
   (sorted-map)
   value))

(defn canonical-data
  "Normalize supported JSON data into RFC 8785 member order."
  [value]
  (cond
    (map? value) (normalize-map value)
    (sequential? value) (mapv canonical-data value)
    (string? value) (normalize-string value)
    (or (nil? value)
        (boolean? value)
        (and (integer? value)
             (<= (- maximum-safe-integer) value maximum-safe-integer))) value
    :else (fail! "Canonical protocol JSON contains an unsupported value"
                 {:value value :type (type value)})))

(defn canonical-json
  "Return RFC 8785 canonical JSON for the protocol's integral JSON domain."
  [value]
  (json/write-str (canonical-data value)
                  :escape-slash false
                  :escape-unicode false
                  :escape-js-separators false))

(defn sha256
  "Return the lowercase SHA-256 digest of UTF-8 `text`."
  [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String text StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn canonical-sha256
  "Return SHA-256 of RFC 8785 canonical JSON `value`."
  [value]
  (sha256 (canonical-json value)))

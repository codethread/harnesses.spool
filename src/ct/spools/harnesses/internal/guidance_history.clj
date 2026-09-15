(ns ct.spools.harnesses.internal.guidance-history
  "Attempt-scoped evidence retained when guidance history is retired."
  (:require [clojure.string :as str]
            [millstrand.api.spool.alpha :as spool])
  (:import [java.time Instant]
           [java.time.format DateTimeParseException]))

(def retirement-key
  "Attempt-record key containing authoritative retirement evidence."
  "retired-evidence")

(def ^:private evidence-keys
  #{"attempt" "invocation" "authority"
    "attachment-session-id" "attachment-at" "attachment-source"
    "completion-owner-pid" "completion-owner-started-at"
    "completion-owner-host" "completion-owner-invocation"
    "provider-pid" "provider-started-at" "provider-host"
    "provider-invocation" "process-key" "process-handle"
    "settlement" "exit-code"})

(def ^:private custody-roles
  [{:prefix "completion-owner"
    :pid :harness/completion-owner-pid
    :started-at :harness/completion-owner-started-at
    :host :harness/completion-owner-host
    :invocation :harness/completion-owner-invocation}
   {:prefix "provider"
    :pid :harness/provider-pid
    :started-at :harness/provider-started-at
    :host :harness/provider-host
    :invocation :harness/provider-invocation}])

(def ^:private attachment-keys
  #{"attachment-session-id" "attachment-at" "attachment-source"})

(defn- attribute [run key]
  (get (:attributes run) key))

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn- instant? [value]
  (and (nonblank? value)
       (try
         (Instant/parse value)
         true
         (catch DateTimeParseException _ false))))

(defn- exact-attachment [run record]
  (let [attempt (get record "attempt")
        invocation (get record "invocation")
        evidence {:attachment-session-id (attribute run :harness/session-id)
                  :attachment-at (attribute run :harness/native-attached-at)
                  :attachment-source
                  (attribute run :harness/native-attachment-source)
                  :attachment-attempt
                  (attribute run :harness/native-attachment-attempt)
                  :attachment-invocation
                  (attribute run :harness/native-attachment-invocation)}
        present? (some some? (vals (dissoc evidence :attachment-session-id)))]
    (when present?
      (when-not (and (= "true" (attribute run :harness/native-attached))
                     (= attempt (:attachment-attempt evidence))
                     (= invocation (:attachment-invocation evidence))
                     (nonblank? (:attachment-session-id evidence))
                     (nonblank? (:attachment-at evidence))
                     (= "managed-startup" (:attachment-source evidence)))
        (spool/fail! "Guidance attachment evidence is not attempt-fenced" {}))
      (select-keys evidence
                   [:attachment-session-id :attachment-at
                    :attachment-source]))))

(defn- exact-custody [run record role]
  (let [{:keys [prefix pid started-at host invocation]} role
        values {:pid (attribute run pid)
                :started-at (attribute run started-at)
                :host (attribute run host)
                :invocation (attribute run invocation)}]
    (when (= (get record "invocation") (:invocation values))
      (when-not (and (pos-int? (:pid values))
                     (nonblank? (:started-at values))
                     (nonblank? (:host values)))
        (spool/fail! "Guidance custody evidence is incomplete"
                     {:role prefix}))
      (into {} (map (fn [[key value]]
                      [(str prefix "-" (name key)) value]))
            values))))

(defn- exact-process [run record]
  (let [key (attribute run :harness/process-key)
        handle (attribute run :harness/process-handle)]
    (when (= (str (:id run) "/attempt-" (get record "attempt")) key)
      (when-not (some? handle)
        (spool/fail! "Guidance process evidence has no retained handle" {}))
      {:process-key key :process-handle handle})))

(defn retire
  "Return `record` with authoritative current-run evidence retained."
  [run record]
  (let [attachment (exact-attachment run record)
        custody (keep #(exact-custody run record %) custody-roles)
        process (exact-process run record)
        settlement (attribute run :harness/settlement)
        exit-code (attribute run :harness/exit-code)
        evidence
        (cond-> {"attempt" (get record "attempt")
                 "invocation" (get record "invocation")
                 "authority" "harness-retirement/v1"}
          attachment
          (merge (into {} (map (fn [[key value]] [(name key) value]))
                       attachment))
          (seq custody) (merge (apply merge custody))
          process (merge (into {} (map (fn [[key value]] [(name key) value]))
                               process))
          (nonblank? settlement) (assoc "settlement" settlement)
          (some? exit-code) (assoc "exit-code" exit-code))]
    (assoc record retirement-key evidence)))

(defn- attachment-valid? [evidence]
  (let [present (set (filter #(contains? evidence %) attachment-keys))]
    (or (empty? present)
        (and (= attachment-keys present)
             (nonblank? (get evidence "attachment-session-id"))
             (instant? (get evidence "attachment-at"))
             (= "managed-startup"
                (get evidence "attachment-source"))))))

(defn- process-valid? [run record evidence]
  (let [key? (contains? evidence "process-key")
        handle? (contains? evidence "process-handle")]
    (and (= key? handle?)
         (or (not key?)
             (and (= (str (:id run) "/attempt-" (get record "attempt"))
                     (get evidence "process-key"))
                  (some? (get evidence "process-handle")))))))

(defn- custody-valid? [record evidence {:keys [prefix]}]
  (let [keys (set (map #(str prefix "-" %)
                       ["pid" "started-at" "host" "invocation"]))
        present (set (filter #(contains? evidence %) keys))]
    (or (empty? present)
        (and (= keys present)
             (pos-int? (get evidence (str prefix "-pid")))
             (instant? (get evidence (str prefix "-started-at")))
             (nonblank? (get evidence (str prefix "-host")))
             (= (get record "invocation")
                (get evidence (str prefix "-invocation")))))))

(defn- launch-evidence? [evidence]
  (or (some #(contains? evidence %)
            ["attachment-session-id" "completion-owner-invocation"
             "provider-invocation" "process-key"])
      (= "process-exit" (get evidence "settlement"))
      (contains? evidence "exit-code")))

(defn validate-retired!
  "Validate one retired native record and its attempt-scoped evidence."
  [run record]
  (let [evidence (get record retirement-key)]
    (when-not (and (map? evidence)
                   (every? evidence-keys (keys evidence))
                   (= (get record "attempt") (get evidence "attempt"))
                   (= (get record "invocation")
                      (get evidence "invocation"))
                   (= "harness-retirement/v1" (get evidence "authority"))
                   (attachment-valid? evidence)
                   (process-valid? run record evidence)
                   (every? #(custody-valid? record evidence %)
                           custody-roles)
                   (nonblank? (get evidence "settlement"))
                   (or (not (contains? evidence "exit-code"))
                       (integer? (get evidence "exit-code")))
                   (or (not= "process-exit" (get evidence "settlement"))
                       (contains? evidence "exit-code")))
      (spool/fail! "Guidance retired attempt evidence is malformed" {}))
    (when (contains? record "no-launch")
      (when-not (= "launch-not-started" (get evidence "settlement"))
        (spool/fail! "Guidance no-launch retirement is not launch-free" {}))
      (when (launch-evidence? evidence)
        (spool/fail!
         "Guidance no-launch provenance conflicts with retired evidence" {})))
    (when-let [first-fetch (get record "first-fetch")]
      (when-not (and (= (get first-fetch "native-session-id")
                        (get evidence "attachment-session-id"))
                     (= (get first-fetch "fetched-at")
                        (get evidence "attachment-at"))
                     (= "managed-startup"
                        (get evidence "attachment-source")))
        (spool/fail! "Guidance first fetch conflicts with retired attachment"
                     {})))
    record))

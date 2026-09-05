(ns ct.spools.harnesses.providers.internal.outcome
  "Shared provider-boundary helpers for native session evidence and outcomes.

  Providers differ in where a session id comes from. Claude and Pi pin the
  caller's id with `--session-id`; Codex and Cursor mint their own and only ever
  see an id when resuming. A run may therefore hold a `harness/session-id`
  attribute that names no native session at all, and reporting it back as
  confirmed would rebind a logical identity onto history that was never written.
  These helpers keep that distinction explicit so every adapter classifies
  evidence the same way."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private clip-limit 4000)

(defn clipped
  "Return `s` bounded to the provider error budget, or nil when blank."
  [s]
  (when-not (str/blank? s)
    (subs s 0 (min clip-limit (count s)))))

(defn jsonl-records
  "Parse JSONL `stdout` leniently into `{:records [...] :truncated? bool}`.

  A killed or timed-out agent leaves a half-written final line. Every complete
  record before that point is still valid provider output, so parsing stops at
  the first undecodable line and reports the truncation rather than discarding
  the evidence."
  [stdout]
  (loop [lines (remove str/blank? (str/split-lines (or stdout "")))
         records []]
    (if-let [line (first lines)]
      (if-let [record (try
                        (json/read-str line :key-fn keyword)
                        (catch Exception _ nil))]
        (recur (rest lines) (conj records record))
        {:records records :truncated? true})
      {:records records :truncated? false})))

(defn undecodable-error
  "Describe undecodable JSONL, separating a truncated tail from unusable output."
  [provider {:keys [records]} stdout]
  (str provider
       (if (seq records)
         " produced truncated JSONL: "
         " JSONL parse failed: ")
       (or (clipped stdout) "<blank>")))

(defn native-session-id
  "Return the first nonblank id `f` extracts from `records`."
  [records f]
  (some #(let [id (f %)]
           (when-not (str/blank? id) id))
        records))

(defn- clean
  "Drop nil-valued keys so provisional evidence never emits a null id."
  [outcome]
  (into {} (remove (comp nil? val)) outcome))

(defn done
  "Build a successful outcome. `session` is a map from `session-evidence`.

  Core's outcome contract now carries :session-usable, so usable? evidence is
  emitted straight through rather than withheld behind a runtime spec probe."
  [{:keys [exit-code result session]}]
  (clean {:status :done
          :exit-code exit-code
          :result result
          :session-id (:id session)
          :session-usable (:usable? session)}))

(defn failed
  "Build a failure outcome preserving partial result text and session evidence."
  [{:keys [exit-code error result session]}]
  (clean {:status :failed
          :exit-code exit-code
          :result result
          :session-id (:id session)
          :session-usable (:usable? session)
          :error (or (clipped error) "Harness process failed")}))

(defn session-evidence
  "Classify what a finished run proves about resumable native history.

  `observed-id` is an id read out of the provider's own output and is always
  positive proof. Otherwise a resumed run still names the native id that an
  earlier run confirmed, and a provider that pinned the id with a `--session-id`
  flag has proven it only by exiting cleanly. Anything else — most importantly a
  new Codex or Cursor run, whose `known-id` the native CLI never received — is
  provisional and must not be reported as a session at all."
  [{:keys [observed-id known-id resumes? pinned? exit-code]}]
  (let [observed (when-not (str/blank? observed-id) observed-id)
        known (when-not (str/blank? known-id) known-id)]
    (cond
      observed {:id observed :usable? true :origin :observed}
      (and resumes? known) {:id known :usable? true :origin :frozen}
      (and pinned? known (zero? exit-code)) {:id known :usable? true :origin :pinned}
      pinned? {:id known :usable? false :origin :unproven}
      :else {:id nil :usable? false :origin :provisional})))

(ns ct.spools.harnesses.internal.managed-startup
  "Managed Codex/Pi identity reservation and native startup attachment."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [millhouse.spools.identity :as identity]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(def managed-bootstrap-schema
  "Version identifying the prompt-free managed launcher document."
  "millstrand.agent-managed-bootstrap/v1")

(def managed-context-schema
  "Version identifying managed context returned after native attachment."
  "millstrand.agent-managed-context/v1")

(def ^:private managed-harnesses #{"codex" "pi"})
(def ^:private root-scope "root")
(def ^:private bootstrap-keys
  #{"schema" "run-id" "harness" "identity" "reservation-id" "cwd"
    "workspace" "attempt" "invocation" "scope" "expected-native-session-id"})
(def ^:private required-bootstrap-keys
  #{"schema" "run-id" "harness" "identity" "reservation-id" "cwd"
    "workspace" "attempt" "invocation" "scope"})
(def ^:private startup-request-keys
  #{:harness :native-session-id :cwd :scope :bootstrap})

(s/def ::runtime map?)
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::harness ::non-blank-string)
(s/def ::native-session-id ::non-blank-string)
(s/def ::cwd ::non-blank-string)
(s/def ::scope ::non-blank-string)
(s/def ::bootstrap map?)
(s/def ::startup-request
  (s/and (s/keys :req-un [::harness ::native-session-id ::cwd ::scope
                          ::bootstrap])
         #(every? startup-request-keys (keys %))))

(defn managed-harness?
  "Return whether concrete `harness` uses managed native attachment."
  [harness]
  (contains? managed-harnesses harness))

(defn identity-instruction
  "Return the canonical managed identity instruction for `friendly-id`."
  [friendly-id]
  (str "Your Millstrand identity is " friendly-id
       ". Use " friendly-id
       " for identity-bearing operations; pass `--by-identity " friendly-id
       "` explicitly. Do not invent another identity."))

(defn- require-run [rt id]
  (let [run (or (weaver/show rt id)
                (fail! "Managed startup run not found" {:run-id id}))]
    (when-not (= "true" (attr-get run :harness/run))
      (fail! "Managed startup target is not a harness run" {:run-id id}))
    run))

(defn- require-caller [rt by-identity]
  (when by-identity
    (identity/current rt by-identity)))

(defn- provenance!
  [rt identity-strand run caller]
  (let [self? (= (:id caller) (:id identity-strand))]
    (batch/apply!
     rt
     {:refs (cond-> {:identity (:id identity-strand)
                     :run (:id run)}
              (and caller (not self?)) (assoc :caller (:id caller)))
      :strands []
      :edges (cond-> [{:op :upsert
                       :from :identity
                       :to :run
                       :type "performed"}]
               (and caller (not self?))
               (conj {:op :upsert
                      :from :caller
                      :to :identity
                      :type "parent-of"}))
      :burn []})))

(defn commit-identity!
  "Bind or reserve identity and provenance before a managed run is published."
  [rt {:keys [harness session-id run predecessor by-identity effective]}]
  (let [caller (require-caller rt by-identity)]
    (if-not (managed-harness? harness)
      (let [binding (identity/bind!
                     rt
                     (cond-> {:harness harness
                              :native-session-id session-id
                              :run-id (:id run)}
                       predecessor
                       (assoc :expected-identity
                              (attr-get predecessor :identity/id))))]
        (when (and caller (not= (:id caller) (:strand-id binding)))
          (weaver/update!
           rt (:id caller)
           {:edges [{:type "parent-of" :to (:strand-id binding)}]}))
        binding)
      (if predecessor
        (let [reservation-id (attr-get predecessor :identity/reservation-id)
              friendly-id (attr-get predecessor :identity/id)
              attached? (= "true" (attr-get predecessor :harness/native-attached))]
          (when-not (and reservation-id friendly-id attached?)
            (fail! "Managed native resume requires an attached predecessor identity"
                   {:predecessor (:id predecessor)
                    :identity friendly-id
                    :reservation-id reservation-id
                    :native-attached attached?}))
          (let [attached (identity/attach!
                          rt
                          (cond-> {:harness harness
                                   :native-session-id session-id
                                   :reservation-id reservation-id
                                   :identity friendly-id
                                   :run-id (:id run)}
                            by-identity (assoc :parent-identity by-identity)))]
            {:identity (:identity attached)
             :strand-id (:strand-id attached)
             :prompt (:instruction attached)
             :reservation-id reservation-id
             :native-attached true}))
        (let [reservation (identity/reserve!
                           rt
                           (cond-> {:harness harness}
                             (string? (:harness/model effective))
                             (assoc :model (:harness/model effective))
                             (string? (:harness/effort effective))
                             (assoc :thinking-level
                                    (:harness/effort effective))))
              identity-strand (identity/current rt (:identity reservation))]
          (provenance! rt identity-strand run caller)
          {:identity (:identity reservation)
           :strand-id (:strand-id reservation)
           :prompt (identity-instruction (:identity reservation))
           :reservation-id (:reservation-id reservation)
           :native-attached false})))))

(defn retry-identity!
  "Return a coherent identity binding for one managed retry.

  Ordinary retries reserve a fresh identity. Retrying a native-resume run keeps
  its attached session identity and refuses a provider change. Maintenance-only
  providers return nil and retain their existing compatibility path."
  [rt run harness session-id effective]
  (when (managed-harness? harness)
    (if (attr-get run :harness/resumes)
      (do
        (when-not (= harness (attr-get run :harness/harness))
          (fail! "Native resume retry cannot change its managed provider"
                 {:run-id (:id run)
                  :retained (attr-get run :harness/harness)
                  :requested harness}))
        (when-not (= "true" (attr-get run :harness/native-attached))
          (fail! "Native resume retry requires an attached identity"
                 {:run-id (:id run)}))
        {:identity (attr-get run :identity/id)
         :prompt (attr-get run :identity/prompt)
         :reservation-id (attr-get run :identity/reservation-id)
         :native-attached true})
      (let [caller (require-caller rt
                                   (attr-get run :harness/caller-identity))
            reservation (identity/reserve!
                         rt
                         (cond-> {:harness harness}
                           (string? (:harness/model effective))
                           (assoc :model (:harness/model effective))
                           (string? (:harness/effort effective))
                           (assoc :thinking-level
                                  (:harness/effort effective))))
            identity-strand (identity/current rt (:identity reservation))]
        (provenance! rt identity-strand run caller)
        {:identity (:identity reservation)
         :strand-id (:strand-id reservation)
         :prompt (identity-instruction (:identity reservation))
         :reservation-id (:reservation-id reservation)
         :native-attached false
         :session-id session-id}))))

(defn- workspace [rt]
  (or (get-in rt [:metadata :config-dir])
      (fail! "Managed startup requires a selected workspace" {})))

(defn- canonical-path [path label]
  (when-not (and (string? path) (not (str/blank? path)))
    (fail! (str "Managed startup requires " label) {label path}))
  (.getCanonicalPath (io/file path)))

(defn bootstrap
  "Return prompt-free managed startup metadata for a running invocation.

  Maintenance providers return nil. Codex/Pi runs must be published and carry
  the reservation, attempt, and invocation minted for this exact launch."
  [rt run]
  (when (managed-harness? (attr-get run :harness/harness))
    (let [attempt (attr-get run :harness/attempt)
          invocation (attr-get run :harness/invocation)
          reservation-id (attr-get run :identity/reservation-id)
          identity-id (attr-get run :identity/id)
          harness (attr-get run :harness/harness)
          attached? (= "true" (attr-get run :harness/native-attached))]
      (when-not (= "true" (attr-get run :harness/published))
        (fail! "Managed startup run is not published" {:run-id (:id run)}))
      (when-not (and (pos-int? attempt)
                     (string? invocation) (not (str/blank? invocation)))
        (fail! "Managed startup run has no active invocation"
               {:run-id (:id run) :attempt attempt :invocation invocation}))
      (when-not (and (string? reservation-id) (not (str/blank? reservation-id))
                     (string? identity-id) (not (str/blank? identity-id)))
        (fail! "Managed startup run has no identity reservation"
               {:run-id (:id run)}))
      (cond-> {"schema" managed-bootstrap-schema
               "run-id" (:id run)
               "harness" harness
               "identity" identity-id
               "reservation-id" reservation-id
               "cwd" (canonical-path (attr-get run :harness/cwd) "cwd")
               "workspace" (canonical-path (workspace rt) "workspace")
               "attempt" attempt
               "invocation" invocation
               "scope" root-scope}
        (= "pi" harness)
        (assoc "expected-native-session-id"
               (attr-get run :harness/provisional-session-id))
        (and (not= "pi" harness) attached?)
        (assoc "expected-native-session-id"
               (attr-get run :harness/session-id))))))

(defn- normalize-bootstrap [bootstrap]
  (let [entries (map (fn [[k value]] [(name k) value]) bootstrap)
        names (map first entries)]
    (when-not (= (count names) (count (distinct names)))
      (fail! "Managed startup bootstrap has duplicate keys"
             {:keys (vec names)}))
    (let [normalized (into {} entries)
          keys (set (keys normalized))]
      (when-not (and (every? bootstrap-keys keys)
                     (every? keys required-bootstrap-keys))
        (fail! "Managed startup bootstrap has an invalid schema"
               {:required (sort required-bootstrap-keys)
                :allowed (sort bootstrap-keys)
                :actual (sort keys)}))
      normalized)))

(defn- attribute-name [attribute]
  (str (namespace attribute) "/" (name attribute)))

(defn- reserving-writers [rt attribute value]
  (filterv #(and (= "true" (attr-get % :harness/run))
                 (= "true" (attr-get % :harness/published))
                 (life/reserving? %))
           (weaver/list rt [:= [:attr (attribute-name attribute)] value] {})))

(defn- validate-attachment!
  [rt run {:keys [harness native-session-id cwd scope bootstrap]}]
  (let [stored-harness (attr-get run :harness/harness)
        stored-cwd (canonical-path (attr-get run :harness/cwd) "cwd")
        stored-workspace (canonical-path (workspace rt) "workspace")
        attached-id (when (= "true" (attr-get run :harness/native-attached))
                      (attr-get run :harness/session-id))
        conflicts (remove #(= (:id run) (:id %))
                          (reserving-writers rt :harness/session-id
                                             native-session-id))
        target (attr-get run :harness/target)
        target-conflicts (when target
                           (remove #(= (:id run) (:id %))
                                   (reserving-writers rt :harness/target target)))]
    (when-not (managed-harness? stored-harness)
      (fail! "Managed startup applies only to Codex and Pi"
             {:run-id (:id run) :harness stored-harness}))
    (when-not (= "true" (attr-get run :harness/published))
      (fail! "Managed startup run is not published" {:run-id (:id run)}))
    (when-not (contains? #{"running" "stopped" "failed"}
                         (attr-get run :harness/status))
      (fail! "Managed startup run has not begun"
             {:run-id (:id run)
              :status (attr-get run :harness/status)}))
    (doseq [[label expected actual]
            [["schema" managed-bootstrap-schema (get bootstrap "schema")]
             ["run" (:id run) (get bootstrap "run-id")]
             ["harness" stored-harness harness]
             ["bootstrap harness" stored-harness (get bootstrap "harness")]
             ["identity" (attr-get run :identity/id)
              (get bootstrap "identity")]
             ["reservation" (attr-get run :identity/reservation-id)
              (get bootstrap "reservation-id")]
             ["cwd" stored-cwd (canonical-path cwd "cwd")]
             ["bootstrap cwd" stored-cwd
              (canonical-path (get bootstrap "cwd") "bootstrap cwd")]
             ["workspace" stored-workspace
              (canonical-path (get bootstrap "workspace")
                              "bootstrap workspace")]
             ["attempt" (attr-get run :harness/attempt)
              (get bootstrap "attempt")]
             ["invocation" (attr-get run :harness/invocation)
              (get bootstrap "invocation")]
             ["scope" root-scope scope]
             ["bootstrap scope" root-scope (get bootstrap "scope")]]]
      (when-not (= expected actual)
        (fail! (str "Managed startup " label " does not match the run")
               {:run-id (:id run) :expected expected :actual actual})))
    (when-let [expected-native (get bootstrap "expected-native-session-id")]
      (when-not (= expected-native native-session-id)
        (fail! "Managed startup native session does not match the launch"
               {:run-id (:id run)
                :expected expected-native
                :actual native-session-id})))
    (when (and attached-id (not= attached-id native-session-id))
      (fail! "Managed run is already attached to another native session"
             {:run-id (:id run)
              :expected attached-id
              :actual native-session-id}))
    (when (and (nil? attached-id) (seq conflicts))
      (fail! "Native session already has an active managed writer"
             {:session-id native-session-id
              :runs (mapv :id conflicts)}))
    (when (and (nil? attached-id) (seq target-conflicts))
      (fail! "Managed startup target has another active writer"
             {:target target
              :runs (mapv :id target-conflicts)}))))

(defn- context-result [run attached]
  {:schema managed-context-schema
   :run-id (:id run)
   :harness (attr-get run :harness/harness)
   :native-session-id (attr-get run :harness/session-id)
   :identity (:identity attached)
   :strand-id (:strand-id attached)
   :result (:result attached)
   :instruction (:instruction attached)
   :context {:schema managed-context-schema
             :identity-instruction (:instruction attached)
             :appended-system-prompts
             (or (attr-get run :harness/appended-system-prompts) [])}})

(defn- attach-validated!
  [rt run native-session-id]
  (let [attachment-recorded?
        (and (= "true" (attr-get run :harness/native-attached))
             (= (attr-get run :harness/invocation)
                (attr-get run :harness/native-attachment-invocation)))
        attached (identity/attach!
                  rt
                  (cond-> {:harness (attr-get run :harness/harness)
                           :native-session-id native-session-id
                           :reservation-id
                           (attr-get run :identity/reservation-id)
                           :identity (attr-get run :identity/id)
                           :run-id (:id run)}
                    (attr-get run :harness/caller-identity)
                    (assoc :parent-identity
                           (attr-get run :harness/caller-identity))))
        updated (if attachment-recorded?
                  run
                  (weaver/update!
                   rt (:id run)
                   {:attributes
                    {:harness/session-id native-session-id
                     :harness/native-attached "true"
                     :harness/native-attached-at (str (java.time.Instant/now))
                     :harness/native-attachment-source "managed-startup"
                     :harness/native-attachment-attempt
                     (attr-get run :harness/attempt)
                     :harness/native-attachment-invocation
                     (attr-get run :harness/invocation)}}))]
    (context-result updated attached)))

(defn startup!
  "Attach one managed run to its actual root native session.

  The explicit bootstrap is checked against the durable run and current launch
  fence before identity attachment. Exact replay converges; stale, child,
  conflicting, or differently bound requests fail before identity writes."
  [rt request]
  (require-valid! ::runtime rt "startup! requires a Weaver runtime")
  (require-valid! ::startup-request request
                  "startup! requires a valid managed startup request")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [request (update request :bootstrap normalize-bootstrap)
          run (require-run rt (get-in request [:bootstrap "run-id"]))]
      (validate-attachment! rt run request)
      (attach-validated! rt run (:native-session-id request)))))

(defn attach-outcome!
  "Attach positive provider session evidence to a managed invocation.

  Returns nil when the provider is maintenance-only or supplied no usable
  native evidence. The caller must hold the lifecycle publication lock."
  [rt run {:keys [session-id session-usable invocation]}]
  (when (and (managed-harness? (attr-get run :harness/harness))
             session-usable session-id)
    (when-not (and (string? invocation)
                   (not (str/blank? invocation))
                   (= invocation (attr-get run :harness/invocation)))
      (fail! "Managed provider evidence has a missing or stale invocation"
             {:run-id (:id run)
              :expected (attr-get run :harness/invocation)
              :actual invocation}))
    (let [bootstrap (bootstrap rt run)
          request {:harness (attr-get run :harness/harness)
                   :native-session-id session-id
                   :cwd (attr-get run :harness/cwd)
                   :scope root-scope
                   :bootstrap bootstrap}]
      (validate-attachment! rt run request)
      (attach-validated! rt run session-id))))

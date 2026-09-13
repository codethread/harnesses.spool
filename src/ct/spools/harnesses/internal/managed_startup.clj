(ns ct.spools.harnesses.internal.managed-startup
  "Managed Codex/Pi identity reservation and native startup attachment."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-identity :as managed-identity]
            [millhouse.spools.identity :as identity]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.graph.alpha :as graph]
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

(defn legacy-managed-run?
  "Return whether `run` has the pre-reservation managed representation.

  Accepted legacy Codex/Pi rows have a published identity binding but none of
  the three fields introduced by reservation-backed startup. A partially
  damaged current row retains at least its native attachment or provisional
  session field and must continue through the strict startup-v1 path."
  [run]
  (and (managed-harness? (attr-get run :harness/harness))
       (= "true" (attr-get run :harness/published))
       (nil? (attr-get run :identity/reservation-id))
       (nil? (attr-get run :harness/native-attached))
       (nil? (attr-get run :harness/provisional-session-id))))

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

(declare require-legacy-pi-continuation!)

(defn- legacy-pi-identity!
  [rt run predecessor caller]
  (require-legacy-pi-continuation! rt predecessor)
  (let [binding (identity/bind!
                 rt
                 {:harness "pi"
                  :native-session-id (attr-get predecessor :harness/session-id)
                  :run-id (:id run)
                  :expected-identity (attr-get predecessor :identity/id)})]
    (when (and caller (not= (:id caller) (:strand-id binding)))
      (weaver/update!
       rt (:id caller)
       {:edges [{:type "parent-of" :to (:strand-id binding)}]}))
    (assoc binding :legacy-pi true)))

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
        (if (legacy-managed-run? predecessor)
          (legacy-pi-identity! rt run predecessor caller)
          (let [reservation-id (attr-get predecessor :identity/reservation-id)
                friendly-id (attr-get predecessor :identity/id)
                attached? (= "true" (attr-get predecessor
                                              :harness/native-attached))]
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
               :native-attached true})))
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

  Ordinary retries reserve a fresh identity. Retrying a native resume keeps its
  attached identity. A validated pre-reservation Pi continuation keeps its exact
  legacy binding and transport. Maintenance-only providers return nil."
  [rt run harness session-id effective]
  (when (managed-harness? harness)
    (if (attr-get run :harness/resumes)
      (do
        (when-not (= harness (attr-get run :harness/harness))
          (fail! "Native resume retry cannot change its managed provider"
                 {:run-id (:id run)
                  :retained (attr-get run :harness/harness)
                  :requested harness}))
        (if (legacy-managed-run? run)
          (do
            (require-legacy-pi-continuation! rt run)
            {:identity (attr-get run :identity/id)
             :prompt (attr-get run :identity/prompt)
             :legacy-pi true})
          (do
            (when-not (= "true" (attr-get run :harness/native-attached))
              (fail! "Native resume retry requires an attached identity"
                     {:run-id (:id run)}))
            {:identity (attr-get run :identity/id)
             :prompt (attr-get run :identity/prompt)
             :reservation-id (attr-get run :identity/reservation-id)
             :native-attached true})))
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

  Maintenance providers and validated pre-reservation Pi continuations use the
  legacy launcher transport and return nil. Reservation-backed Codex/Pi runs
  must carry the attempt and invocation minted for this exact launch."
  [rt run]
  (when (managed-harness? (attr-get run :harness/harness))
    (if (legacy-managed-run? run)
      (do
        (when-not (and (string? (attr-get run :harness/resumes))
                       (not (str/blank? (attr-get run :harness/resumes))))
          (fail! "Legacy Pi launch requires a native continuation"
                 {:run-id (:id run)
                  :resumes (attr-get run :harness/resumes)}))
        (require-legacy-pi-continuation! rt run)
        nil)
      (let [attempt (attr-get run :harness/attempt)
            invocation (attr-get run :harness/invocation)
            reservation-id (attr-get run :identity/reservation-id)
            identity-id (attr-get run :identity/id)
            harness (attr-get run :harness/harness)
            provisional-session-id
            (attr-get run :harness/provisional-session-id)
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
        (when (and (= "pi" harness)
                   (not (and (string? provisional-session-id)
                             (not (str/blank? provisional-session-id)))))
          (fail! "Managed Pi startup has no durable native session pin"
                 {:run-id (:id run)
                  :provisional-session-id provisional-session-id}))
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
          (assoc "expected-native-session-id" provisional-session-id)
          (and (not= "pi" harness) attached?)
          (assoc "expected-native-session-id"
                 (attr-get run :harness/session-id)))))))

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
                                   (reserving-writers rt :harness/target target)))
        durable-attempt (attr-get run :harness/attempt)
        durable-invocation (attr-get run :harness/invocation)
        durable-pi-pin (when (= "pi" stored-harness)
                         (attr-get run
                                   :harness/provisional-session-id))]
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
    (when-not (and (pos-int? durable-attempt)
                   (string? durable-invocation)
                   (not (str/blank? durable-invocation)))
      (fail! "Managed startup run has no durable launch fence"
             {:run-id (:id run)
              :attempt durable-attempt
              :invocation durable-invocation}))
    (when (= "pi" stored-harness)
      (when-not (and (string? durable-pi-pin)
                     (not (str/blank? durable-pi-pin)))
        (fail! "Managed Pi startup has no durable native session pin"
               {:run-id (:id run)
                :provisional-session-id durable-pi-pin}))
      (when-not (contains? bootstrap "expected-native-session-id")
        (fail! "Managed Pi startup bootstrap requires its native session pin"
               {:run-id (:id run)}))
      (when-not (= durable-pi-pin
                   (get bootstrap "expected-native-session-id")
                   native-session-id)
        (fail! "Managed Pi startup native session does not match its durable pin"
               {:run-id (:id run)
                :expected durable-pi-pin
                :bootstrap (get bootstrap "expected-native-session-id")
                :actual native-session-id})))
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
             ["attempt" durable-attempt (get bootstrap "attempt")]
             ["invocation" durable-invocation
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

(defn- performed-run? [rt identity-strand run-id]
  (boolean
   (some #(= run-id (:to_strand_id %))
         (graph/outgoing-edges rt [(:id identity-strand)] "performed"))))

(defn- identity-record? [strand]
  (= "true" (attr-get strand :identity/session)))

(defn- identities-for-native-session [rt harness native-session-id]
  (filterv #(and (identity-record? %)
                 (= harness (attr-get % :identity/harness))
                 (= native-session-id
                    (attr-get % :identity/native-session-id)))
           (weaver/list rt)))

(defn- require-legacy-binding!
  [rt run]
  (let [friendly-id (attr-get run :identity/id)]
    (when-not (and (string? friendly-id) (not (str/blank? friendly-id)))
      (fail! "Legacy managed run has no identity binding"
             {:run-id (:id run) :identity friendly-id}))
    (let [identity-strand (identity/current rt friendly-id)
          harness (attr-get run :harness/harness)
          identity-harness (attr-get identity-strand :identity/harness)
          native-session-id
          (attr-get identity-strand :identity/native-session-id)
          reservation-id
          (attr-get identity-strand :identity/reservation-id)
          reservation-state
          (attr-get identity-strand :identity/reservation-state)]
      (when-not (= harness identity-harness)
        (fail! "Legacy managed identity belongs to another harness"
               {:run-id (:id run)
                :identity friendly-id
                :expected harness
                :actual identity-harness}))
      (when-not (and (string? native-session-id)
                     (not (str/blank? native-session-id)))
        (fail! "Legacy managed identity has no native session binding"
               {:run-id (:id run) :identity friendly-id}))
      (when (or reservation-id reservation-state)
        (fail! "Legacy managed identity has reservation metadata"
               {:run-id (:id run)
                :identity friendly-id
                :reservation-id reservation-id
                :reservation-state reservation-state}))
      (when-not (performed-run? rt identity-strand (:id run))
        (fail! "Legacy managed identity did not perform the run"
               {:run-id (:id run) :identity friendly-id}))
      identity-strand)))

(defn require-legacy-pi-continuation!
  "Return the exact identity binding for a safe legacy Pi continuation.

  The run must use the pre-reservation representation and retain one unique Pi
  identity whose native session exactly equals the run's stored session. The
  identity must have performed this run."
  [rt run]
  (when-not (legacy-managed-run? run)
    (fail! "Legacy Pi continuation requires a pre-reservation run"
           {:run-id (:id run)}))
  (when-not (= "pi" (attr-get run :harness/harness))
    (fail! "Legacy native continuation applies only to exact-binding Pi runs"
           {:run-id (:id run)
            :harness (attr-get run :harness/harness)}))
  (let [identity-strand (require-legacy-binding! rt run)
        run-session-id (attr-get run :harness/session-id)
        native-session-id
        (attr-get identity-strand :identity/native-session-id)
        bindings (when (and (string? run-session-id)
                            (not (str/blank? run-session-id)))
                   (identities-for-native-session rt "pi" run-session-id))]
    (when-not (and (string? run-session-id)
                   (not (str/blank? run-session-id))
                   (= run-session-id native-session-id))
      (fail! "Legacy Pi run session does not match its identity binding"
             {:run-id (:id run)
              :run-session-id run-session-id
              :identity-session-id native-session-id}))
    (when-not (and (= 1 (count bindings))
                   (= (:id identity-strand) (:id (first bindings))))
      (fail! "Legacy Pi native session does not resolve to one identity"
             {:run-id (:id run)
              :session-id run-session-id
              :identities (mapv #(attr-get % :identity/id) bindings)}))
    identity-strand))

(defn- positive-legacy-outcome? [{:keys [status session-usable]}]
  (or (= :done (if (keyword? status) status (keyword status)))
      (true? session-usable)))

(defn require-legacy-positive-attempt!
  "Require a durable launch fence before accepting positive legacy evidence.

  Failed prelaunch outcomes without usable session evidence remain valid and do
  not require an attempt."
  [run {:keys [invocation] :as outcome}]
  (when (and (legacy-managed-run? run)
             (positive-legacy-outcome? outcome))
    (let [attempt (attr-get run :harness/attempt)
          durable-invocation (attr-get run :harness/invocation)]
      (when-not (and (pos-int? attempt)
                     (string? durable-invocation)
                     (not (str/blank? durable-invocation)))
        (fail! "Positive legacy outcome requires a durable launch fence"
               {:run-id (:id run)
                :attempt attempt
                :invocation durable-invocation}))
      (when-not (and (string? invocation) (not (str/blank? invocation)))
        (fail! "Positive legacy outcome requires its invocation token"
               {:run-id (:id run)
                :expected durable-invocation
                :actual invocation}))))
  nil)

(defn require-legacy-positive-invocation!
  "Require the supplied invocation to equal a positive legacy outcome's fence."
  [run {:keys [invocation] :as outcome}]
  (require-legacy-positive-attempt! run outcome)
  (when (and (legacy-managed-run? run)
             (positive-legacy-outcome? outcome))
    (let [durable-invocation (attr-get run :harness/invocation)]
      (when-not (and (string? invocation)
                     (not (str/blank? invocation))
                     (= durable-invocation invocation))
        (fail! "Positive legacy outcome has a missing or stale invocation"
               {:run-id (:id run)
                :expected durable-invocation
                :actual invocation}))))
  nil)

(defn- require-legacy-pi-session-evidence!
  [rt run session-id session-usable]
  (when (and (= "pi" (attr-get run :harness/harness))
             (true? session-usable))
    (let [identity-strand (require-legacy-pi-continuation! rt run)
          durable-session-id (attr-get run :harness/session-id)
          identity-session-id
          (attr-get identity-strand :identity/native-session-id)]
      (when-not (and (string? session-id)
                     (not (str/blank? session-id))
                     (= durable-session-id identity-session-id session-id))
        (fail! "Legacy Pi outcome session does not match its durable binding"
               {:run-id (:id run)
                :expected durable-session-id
                :identity-session-id identity-session-id
                :actual session-id}))))
  nil)

(defn validate-legacy-outcome!
  "Validate identity and invocation before accepting positive legacy evidence.

  Failed outcomes without usable session evidence have nothing to attach and do
  not depend on the historical identity remaining valid."
  [rt run {:keys [session-id session-usable] :as outcome}]
  (when (and (legacy-managed-run? run)
             (positive-legacy-outcome? outcome))
    (require-legacy-positive-invocation! run outcome)
    (require-legacy-binding! rt run)
    (require-legacy-pi-session-evidence!
     rt run session-id session-usable))
  nil)

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

(defn- attachment-result [identity-strand]
  (let [friendly-id (attr-get identity-strand :identity/id)]
    {:identity friendly-id
     :strand-id (:id identity-strand)
     :result "attached"
     :instruction (identity-instruction friendly-id)}))

(defn- attach-validated!
  [rt run native-session-id]
  (managed-identity/with-identity-guard
    rt
    (fn []
      (let [attachment-recorded?
            (and (= "true" (attr-get run :harness/native-attached))
                 (= (attr-get run :harness/invocation)
                    (attr-get run :harness/native-attachment-invocation)))
            {:keys [identity-strand parent already-attached?]}
            (managed-identity/reservation-binding
             rt
             {:harness (attr-get run :harness/harness)
              :native-session-id native-session-id
              :reservation-id (attr-get run :identity/reservation-id)
              :friendly-id (attr-get run :identity/id)
              :parent-identity
              (attr-get run :harness/caller-identity)})]
        (when (and attachment-recorded? (not already-attached?))
          (fail! "Managed run attachment evidence conflicts with its reservation"
                 {:run-id (:id run)
                  :reservation-id
                  (attr-get run :identity/reservation-id)}))
        (if attachment-recorded?
          (context-result run (attachment-result identity-strand))
          (let [{:keys [identity-strand run]}
                (managed-identity/persist-attachment!
                 rt
                 {:identity-strand identity-strand
                  :run run
                  :parent parent
                  :identity-attributes
                  (when-not already-attached?
                    {:identity/native-session-id native-session-id
                     :identity/reservation-state "attached"})
                  :run-attributes
                  {:harness/session-id native-session-id
                   :harness/native-attached "true"
                   :harness/native-attached-at
                   (str (java.time.Instant/now))
                   :harness/native-attachment-source "managed-startup"
                   :harness/native-attachment-attempt
                   (attr-get run :harness/attempt)
                   :harness/native-attachment-invocation
                   (attr-get run :harness/invocation)}})]
            (context-result run (attachment-result identity-strand))))))))

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

  A genuine pre-reservation run keeps its historical identity binding and
  records no invented attachment evidence. Its binding and `performed`
  provenance are still validated before completion is accepted.

  Returns a legacy result after validating positive pre-reservation evidence.
  Returns nil for maintenance providers and failed legacy outcomes without a
  usable session. The caller must hold the lifecycle publication lock."
  [rt run {:keys [session-id session-usable invocation] :as outcome}]
  (when (managed-harness? (attr-get run :harness/harness))
    (if (legacy-managed-run? run)
      (when (positive-legacy-outcome? outcome)
        (validate-legacy-outcome! rt run outcome)
        {:result "legacy"})
      (when (and session-usable session-id)
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
          (attach-validated! rt run session-id))))))

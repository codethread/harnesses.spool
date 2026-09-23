(ns ct.spools.harnesses.internal.native-registration
  "Register observed native sessions without reserving identity or spawning work."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-identity :as binding]
            [millhouse.spools.identity :as identity]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::harness #{"pi"})
(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::native-session-id ::text)
(s/def ::cwd ::text)
(s/def ::run-id ::text)
(s/def ::parent-identity ::text)
(s/def ::parent-native-session-id ::text)
(s/def ::model ::text)
(s/def ::thinking-level ::text)
(s/def ::request
  (s/and (s/keys :req-un [::harness ::native-session-id ::cwd]
                 :opt-un [::run-id ::parent-identity ::parent-native-session-id ::model ::thinking-level])
         #(every? #{:harness :native-session-id :cwd :run-id :parent-identity
                    :parent-native-session-id :model :thinking-level} (keys %))))

(defn external?
  "Return whether a run records a session whose process Harnesses does not own."
  [run]
  (= "external" (attr-get run :harness/ownership)))

(defn- canonical [path]
  (.getCanonicalPath (io/file path)))

(defn- managed-run! [rt {:keys [run-id harness native-session-id cwd]}]
  (let [run (weaver/show rt run-id)]
    (when-not (and (= "true" (attr-get run :harness/run))
                   (not (external? run))
                   (life/accepted? run)
                   (= "running" (life/status run))
                   (pos-int? (attr-get run :harness/attempt))
                   (not (str/blank? (life/invocation run)))
                   (= harness (attr-get run :harness/harness))
                   (= native-session-id (attr-get run :harness/session-id))
                   (= (canonical cwd) (canonical (attr-get run :harness/cwd))))
      (fail! "Native startup does not match a running managed session"
             {:run-id run-id :harness harness :native-session-id native-session-id}))
    run))

(defn- external-registration
  "Return the existing external registration for one native session.

  Reject an active managed writer or duplicate registrations without writing,
  so a caller creates the run only after identity resolution succeeds."
  [rt {:keys [harness native-session-id]}]
  (let [registrations (weaver/list rt [:and [:= [:attr "harness/run"] "true"]
                                       [:= [:attr "harness/harness"] harness]
                                       [:= [:attr "harness/session-id"] native-session-id]] {})
        matches (filterv external? registrations)]
    (when (some #(and (not (external? %)) (life/reserving? %)) registrations)
      (fail! "Native session already has an active managed writer"
             {:harness harness :native-session-id native-session-id}))
    (when (< 1 (count matches))
      (fail! "Native session has duplicate external registrations"
             {:harness harness :native-session-id native-session-id}))
    (first matches)))

(defn- create-external-run!
  "Record an unpublished external session registration.

  Publication commits with the attachment batch, so a rejected registration
  leaves an incomplete, recoverable publication instead of a committed run."
  [rt {:keys [harness native-session-id cwd]}]
  (weaver/add! rt {:title (str harness " native session " native-session-id)
                   :attributes {:harness/run "true"
                                :harness/harness harness
                                :harness/mode "external"
                                :harness/ownership "external"
                                :harness/status "running"
                                :harness/session-id native-session-id
                                :harness/cwd (canonical cwd)
                                :harness/publication-phase "created"
                                :harness/publication-outcome "publishing"}}))

(defn register!
  "Recover native identity and register its observed run before model work.

  Managed correlation names an already-running run and must match its actual
  session. Direct registration is idempotent by provider/session, has no alias,
  and grants no process custody. A direct run is recorded as an incomplete
  publication and commits identity, provenance, and publication in one atomic
  attachment batch, so a rejected registration leaves no committed run.
  Unknown effort is explicit observed metadata, never a provider option.
  Ordinary launch prompts remain outside this API."
  [rt {:keys [harness native-session-id run-id parent-identity parent-native-session-id model thinking-level]
       :as request}]
  (require-valid! ::request request "Invalid native startup request")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [parent (when parent-native-session-id
                   (let [matches (weaver/list rt [:and [:= [:attr "identity/session"] "true"]
                                                  [:= [:attr "identity/harness"] harness]
                                                  [:= [:attr "identity/native-session-id"] parent-native-session-id]] {})]
                     (when-not (= 1 (count matches))
                       (fail! "Native parent session is not registered uniquely"
                              {:parent-native-session-id parent-native-session-id}))
                     (first matches)))
          registered-parent (when parent (attr-get parent :identity/id))
          parent-identity (if registered-parent
                            (do
                              (when (and parent-identity
                                         (not= parent-identity registered-parent))
                                (fail! "Native parent identity does not match its parent session"
                                       {:parent-native-session-id parent-native-session-id
                                        :parent-identity parent-identity
                                        :registered-parent-identity registered-parent}))
                              registered-parent)
                            parent-identity)
          inherited-child? (and run-id parent-native-session-id
                                (not= native-session-id parent-native-session-id)
                                (= parent-native-session-id
                                   (attr-get (weaver/show rt run-id) :harness/session-id)))
          run-id (when-not inherited-child? run-id)
          managed (when run-id (managed-run! rt request))
          existing (when-not run-id (external-registration rt request))
          run (or managed existing (create-external-run! rt request))
          attached (identity/startup!
                    rt (cond-> {:harness harness
                                :native-session-id native-session-id
                                :run-id (:id run)}
                         parent-identity (assoc :parent-identity parent-identity)
                         model (assoc :model model)
                         thinking-level (assoc :thinking-level thinking-level)))
          effort (or thinking-level (attr-get run :harness/effort)
                     (attr-get run :harness/observed-effort) "unknown")
          attrs (cond-> {:identity/id (:identity attached)
                         :harness/observed-effort effort
                         :harness/native-attached "true"
                         :harness/native-attachment-source "native-startup"
                         :harness/native-attached-at (or (attr-get run :harness/native-attached-at)
                                                         (life/now))}
                  model (assoc :harness/observed-model model)
                  run-id (assoc :harness/native-attachment-attempt (attr-get run :harness/attempt)
                                :harness/native-attachment-invocation (life/invocation run))
                  (nil? run-id) (assoc :harness/published "true"
                                       :harness/publication-phase "complete"
                                       :harness/publication-outcome "committed"))
          changed? (some (fn [[k v]] (not= v (attr-get run k))) attrs)]
      (when changed?
        (binding/persist-attachment!
         rt {:identity-strand (identity/current rt (:identity attached))
             :run run :run-attributes attrs}))
      (assoc attached :run-id (:id run) :native-session-id native-session-id
             :observed-effort effort
             :workspace (canonical (get-in rt [:metadata :config-dir]))))))

(defn completion
  "Fail Pi completion visibly when its exact invocation never registered.

  Preserve real process settlement and exit evidence; absence of startup cannot
  be repaired by a provider's final session record."
  [run outcome]
  (if (and (= "pi" (attr-get run :harness/harness))
           (life/invocation run)
           (not (and (= "true" (attr-get run :harness/native-attached))
                     (= (life/invocation run)
                        (attr-get run :harness/native-attachment-invocation)))))
    {:outcome (assoc outcome :status :failed :session-usable false
                     :session-id (attr-get run :harness/session-id)
                     :error "Pi native startup was not recorded for this invocation")
     :evidence (assoc (or (:evidence outcome) (life/settlement-evidence outcome))
                      :failure-class "bootstrap")}
    {:outcome outcome}))

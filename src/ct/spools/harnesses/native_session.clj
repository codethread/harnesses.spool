(ns ct.spools.harnesses.native-session
  "Register observed native sessions without launching or reserving identities."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-identity :as attachment]
            [millhouse.spools.identity :as identity]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::harness #{"codex" "pi"})
(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::native-session-id ::text)
(s/def ::cwd ::text)
(s/def ::model ::text)
(s/def ::thinking-level ::text)
(s/def ::run-reference ::text)
(s/def ::parent-identity ::text)
(s/def ::request
  (s/and (s/keys :req-un [::harness ::native-session-id ::cwd ::model]
                 :opt-un [::thinking-level ::run-reference ::parent-identity])
         #(every? #{:harness :native-session-id :cwd :model :thinking-level
                    :run-reference :parent-identity} (keys %))))

(defn- canonical [path]
  (.getCanonicalPath (io/file path)))

(defn reference
  "Return the minimal run/current-invocation correlation reference."
  [run]
  (let [invocation (life/invocation run)]
    (when-not (and (pos-int? (attr-get run :harness/attempt))
                   (string? invocation) (not (str/blank? invocation)))
      (fail! "Native registration requires an active invocation" {:run-id (:id run)}))
    (str (:id run) ":" invocation)))

(defn- managed-run [rt {:keys [harness native-session-id cwd run-reference
                               parent-identity]}]
  (when parent-identity
    (fail! "A native child cannot attach to a managed root run" {}))
  (let [[id invocation & extra] (str/split run-reference #":" -1)
        run (weaver/show rt id)]
    (when-not (and (empty? extra) (not (str/blank? invocation))
                   (= "true" (attr-get run :harness/run))
                   (life/accepted? run)
                   (= "running" (life/status run))
                   (= invocation (life/invocation run))
                   (pos-int? (attr-get run :harness/attempt))
                   (not= "external" (attr-get run :harness/ownership)))
      (fail! "Native registration has no current managed invocation"
             {:run-reference run-reference}))
    (when-not (and (= harness (attr-get run :harness/harness))
                   (= (canonical cwd) (canonical (attr-get run :harness/cwd))))
      (fail! "Native registration provider or cwd differs from its run" {:run-id id}))
    (when (and (or (attr-get run :harness/resumes)
                   (= "true" (attr-get run :harness/native-attached)))
               (not= native-session-id (attr-get run :harness/session-id)))
      (fail! "Native registration session differs from its run" {:run-id id}))
    (doseq [other (weaver/list rt)
            :when (and (not= id (:id other))
                       (= "true" (attr-get other :harness/run))
                       (life/reserving? other)
                       (or (= native-session-id (attr-get other :harness/session-id))
                           (and (attr-get run :harness/target)
                                (= (attr-get run :harness/target)
                                   (attr-get other :harness/target)))))]
      (fail! "Native registration conflicts with an active writer"
             {:run-id id :writer (:id other)}))
    run))

(defn- direct-run [rt {:keys [harness native-session-id]}]
  (let [matches (filterv #(and (= "true" (attr-get % :harness/run))
                               (= harness (attr-get % :harness/harness))
                               (= native-session-id (attr-get % :harness/session-id)))
                         (weaver/list rt))
        external (filterv #(= "external" (attr-get % :harness/ownership)) matches)]
    (when (or (< 1 (count external))
              (some #(and (not= "external" (attr-get % :harness/ownership))
                          (life/reserving? %)) matches))
      (fail! "Native session has another active registration" {:native-session-id native-session-id}))
    (first external)))

(defn- create-direct-run! [rt {:keys [harness native-session-id cwd model]}]
  (weaver/add!
   rt {:title (str harness " native session " native-session-id)
       :attributes {:harness/run "true"
                    :harness/ownership "external"
                    :harness/harness harness
                    :harness/mode "external"
                    :harness/status "running"
                    :harness/cwd (canonical cwd)
                    :harness/model model
                    :harness/session-id native-session-id
                    :harness/publication-phase "created"
                    :harness/publication-outcome "publishing"}}))

(defn register!
  "Resolve identity and record an observed native session's run participation.

  Managed references fence the exact running invocation. With no reference,
  register an external run once per provider/native session, without an alias,
  target, launch, custody, or settlement assertion. Unknown observed effort is
  the literal `unknown`, never a provider launch option. Return canonical
  identity context plus the registered run ID. Callers gate project discovery
  before invoking this API and route to that project's canonical workspace."
  [rt {:keys [harness native-session-id model thinking-level parent-identity run-reference]
       :as request}]
  (require-valid! ::request request "Invalid native session registration")
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  #_{:splint/disable [lint/locking-object]}
  (locking (catalog/publication-lock rt)
    (let [run (if run-reference (managed-run rt request) (direct-run rt request))
          observed-effort (or thinking-level (attr-get run :harness/effort)
                              (attr-get run :harness/observed-effort) "unknown")
          attached (identity/startup!
                    rt (cond-> {:harness harness :native-session-id native-session-id
                                :model model :thinking-level observed-effort}
                         parent-identity (assoc :parent-identity parent-identity)))
          identity-strand (identity/current rt (:identity attached))
          run (or run (create-direct-run! rt request))
          patch (merge {:identity/id (:identity attached)
                        :harness/session-id native-session-id
                        :harness/observed-model model
                        :harness/observed-effort observed-effort
                        :harness/native-attached "true"
                        :harness/native-attachment-source "native-startup"}
                       (when-not run-reference
                         {:harness/published "true"
                          :harness/publication-phase "complete"
                          :harness/publication-outcome "committed"})
                       (when run-reference
                         {:harness/native-attachment-attempt (attr-get run :harness/attempt)
                          :harness/native-attachment-invocation (life/invocation run)}))]
      (when-not (every? (fn [[k v]] (= v (attr-get run k))) patch)
        (attachment/persist-attachment!
         rt {:identity-strand identity-strand :run run
             :run-attributes (assoc patch :harness/native-attached-at (life/now))}))
      (assoc attached :run-id (:id run) :observed-effort observed-effort))))

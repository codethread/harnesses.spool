(ns ct.spools.harnesses.internal.runs
  "Durable run publication, reservation, and continuation helpers."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.registry :as registry]
            [millhouse.spools.identity :as identity]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- bind-invocation-markers
  "Replace assignment markers with the current published invocation values."
  [value run-id identity-id]
  (cond
    (string? value) (-> value
                        (str/replace "{{RUN_ID}}" run-id)
                        (str/replace "{{AGENT_ID}}" identity-id))
    (map? value) (into {} (map (fn [[key item]]
                                 [key (bind-invocation-markers item run-id identity-id)]))
                       value)
    (sequential? value) (mapv #(bind-invocation-markers % run-id identity-id) value)
    :else value))

(defn require-run
  "Return run `id`, failing when it is absent or not a harness run."
  [rt id]
  (let [run (or (weaver/show rt id) (fail! "Harness run not found" {:id id}))]
    (when-not (= "true" (attr-get run :harness/run))
      (fail! "Strand is not a harness run" {:id id}))
    run))

(defn runs-where
  "List harness runs matching additional query `clauses`."
  [rt clauses]
  (weaver/list rt (into [:and [:= [:attr "harness/run"] "true"]] clauses) {}))

(defn reserving-session-writers
  "Return published runs that still reserve `session-id`."
  [rt session-id]
  (filterv life/reserving?
           (runs-where rt [[:= [:attr "harness/session-id"] session-id]
                           [:= [:attr "harness/published"] "true"]])))

(defn reserving-target-runs
  "Return published runs that still reserve `target`."
  [rt target]
  (filterv life/reserving?
           (runs-where rt [[:= [:attr "harness/target"] target]
                           [:= [:attr "harness/published"] "true"]])))

(defn request-match
  "Return the run already holding `request-id`, or nil when the key is free.

  An equivalent repeat converges on the original run. A different request
  under the same key is a caller bug and fails with the conflicting run's
  ID rather than launching a second agent."
  [rt request-id fingerprint]
  (when request-id
    (let [matches (runs-where rt [[:= [:attr "harness/request-id"] request-id]])]
      (when-let [existing (first matches)]
        (when (next matches)
          (fail! "Request id is held by multiple harness runs"
                 {:request-id request-id :runs (mapv :id matches)}))
        (when-not (= fingerprint (attr-get existing :harness/request-fingerprint))
          (fail! "Request id is already held by a different harness request"
                 {:request-id request-id :run (:id existing)}))
        existing))))

(declare accepted-lineage)

(defn continuation-child
  "Return an accepted continuation of `run-id`, if one exists."
  [rt run-id]
  (let [run (require-run rt run-id)
        child-ids (set (concat
                        (map :from_strand_id
                             (graph/incoming-edges rt [run-id] "resumes"))
                        (map :from_strand_id
                             (graph/incoming-edges rt [run-id] "continues"))))]
    (some #(when (contains? child-ids (:id %)) %)
          (accepted-lineage rt :harness/logical-id
                            (attr-get run :harness/logical-id)))))

(defn require-continuation-head!
  "Reject a predecessor that already has an accepted continuation."
  [rt run-id]
  (when-let [child (continuation-child rt run-id)]
    (fail! "Harness predecessor already has an accepted continuation"
           {:predecessor run-id :continuation (:id child)}))
  (require-run rt run-id))

(defn frozen-resolution
  "Return an alias-free resolution for a native continuation.

  The concrete harness must still be registered, but no alias is consulted, so
  an alias that has been re-pointed or disabled since cannot redirect a live
  session."
  [rt {:keys [alias harness generated env]} concrete-harness]
  (let [harness (registry/name-string harness "Frozen harness")]
    {:alias (or alias harness)
     :harness harness
     :definition (concrete-harness rt harness)
     :generated (registry/normalize-overlay generated)
     :env (or env {})}))

(defn commit-run!
  "Commit one run strand, then publish it once every binding is durable."
  [rt {:keys [title alias harness mode generated env overrides effective cwd
              session-id prompt resumes after target root-targets context request-id fingerprint
              logical-id by-identity]}]
  (when resumes
    (require-continuation-head! rt resumes))
  (when after
    (require-continuation-head! rt after))
  (let [run (require-valid!
             :ct.spools.harnesses/strand
             (weaver/add!
              rt
              (cond-> {:title title
                       :attributes
                       (merge
                        {:harness/run "true"
                         :harness/alias alias
                         :harness/harness harness
                         :harness/mode (name mode)
                         :harness/status "ready"
                         :harness/substatus "pending"
                         :harness/cwd cwd
                         :harness/session-id session-id
                         :harness/env env
                         :harness/generated generated
                         :harness/overrides overrides}
                        effective
                        (when-not (str/blank? prompt)
                          {:harness/prompt prompt})
                        (when resumes {:harness/resumes resumes})
                        (when after {:harness/after after})
                        (when target {:harness/target target})
                        (when (some? root-targets)
                          {:harness/root-targets root-targets})
                        (when context {:harness/context context})
                        (when request-id
                          {:harness/request-id request-id
                           :harness/request-fingerprint fingerprint}))}
                (or resumes after target)
                (assoc :edges (cond-> []
                                resumes (conj {:type "resumes" :to resumes})
                                after (conj {:type "continues" :to after})
                                target (conj {:type "serves" :to target})
                                (seq root-targets)
                                (into (for [root-target root-targets]
                                        {:type "serves-root"
                                         :to root-target}))))))
             "create! produced an invalid run strand")
        predecessor (when resumes (require-run rt resumes))
        identity-binding (identity/bind!
                          rt
                          (cond-> {:harness harness
                                   :native-session-id session-id
                                   :run-id (:id run)}
                            predecessor
                            (assoc :expected-identity
                                   (attr-get predecessor :identity/id))))]
    (when by-identity
      (let [caller (identity/current rt by-identity)]
        (when-not (= (:id caller) (:strand-id identity-binding))
          (weaver/update!
           rt (:id caller)
           {:edges [{:type "parent-of"
                     :to (:strand-id identity-binding)}]}))))
    (let [run-id (:id run)
          identity-id (:identity identity-binding)
          effective (bind-invocation-markers effective run-id identity-id)
          prompt (bind-invocation-markers prompt run-id identity-id)
          context (bind-invocation-markers context run-id identity-id)
          published (require-valid!
                     :ct.spools.harnesses/strand
                     (weaver/update!
                      rt (:id run)
                      {:attributes (merge effective
                                          (when (some? prompt) {:harness/prompt prompt})
                                          (when context {:harness/context context})
                                          {:identity/id identity-id
                                           :identity/prompt (:prompt identity-binding)
                                           :harness/logical-id (or logical-id (:id run))
                                           ;; Last write of the create: everything a scheduler needs
                                           ;; to act on this run is durable before it becomes visible
                                           ;; as published.
                                           :harness/published "true"})})
                     "create! produced an invalid published run")]
      (when-let [predecessor-id (or resumes after)]
        (weaver/update! rt predecessor-id
                        {:attributes {:harness/continued "true"}}))
      published)))

(defn inspectable-headless
  "Return published headless runs that still need a custody observation.

  Running rows and unsettled terminal rows both reserve their session. Ready
  rows have no process. `in-flight?` excludes the launching worker's claim."
  [rt in-flight?]
  (filter #(and (= "true" (attr-get % :harness/run))
                (= "headless" (attr-get % :harness/mode))
                (life/reserving? %)
                (not= "ready" (life/status %))
                (not (in-flight? %)))
          (weaver/list rt
                       [:and
                        [:= [:attr "harness/run"] "true"]
                        [:= [:attr "harness/published"] "true"]
                        [:or
                         [:= [:attr "harness/status"] "running"]
                         [:and
                          [:in [:attr "harness/status"] ["stopped" "failed"]]
                          [:not [:= [:attr "harness/settled"] "true"]]]]]
                       {})))

(defn accepted-lineage
  "Return every published run matching `attribute` = `value`."
  [rt attribute value]
  (filterv #(and (life/published? %)
                 (= value (attr-get % attribute)))
           (runs-where rt [])))

(defn resolve-lineage-head
  "Return the latest accepted head for `attribute` = `value`.

  Every published child is inspected so a still-running continuation keeps
  its predecessor out of the head set. Only a terminal, settled, uncontinued
  run can be a head. There is no fallback to a stale ancestor."
  [rt attribute value selector]
  (let [matches (accepted-lineage rt attribute value)
        continued (set (concat
                        (map :to_strand_id
                             (mapcat #(graph/outgoing-edges rt [(:id %)]
                                                            "resumes")
                                     matches))
                        (map :to_strand_id
                             (mapcat #(graph/outgoing-edges rt [(:id %)]
                                                            "continues")
                                     matches))))
        head (->> matches
                  (filter #(and (life/terminal? %) (life/settled? %)))
                  (remove #(contains? continued (:id %)))
                  (sort-by (juxt :created_at :id) #(compare %2 %1))
                  first)]
    (or head
        (fail! "No settled harness run head matches resume selector"
               {:selector selector}))))

(defn retry-attribute-patch
  "Return the attribute delta that resets one failed run for retry."
  [run {:keys [requested concrete env generated overrides effective cwd]}]
  (let [old-attrs (:attributes run)
        old-generated (registry/normalize-overlay (attr-get run :harness/generated))
        old-overrides (registry/normalize-overlay (attr-get run :harness/overrides))
        old-overlay-keys (set (filter registry/overlay-key? (keys old-attrs)))
        all-overlay-keys (into old-overlay-keys (keys effective))
        overlay-delta (into {} (map (fn [k] [k (get effective k)]) all-overlay-keys))
        generated-delta (into {}
                              (map (fn [k] [k (get generated k)]))
                              (into (set (keys old-generated)) (keys generated)))
        overrides-delta (into {}
                              (map (fn [k] [k (get overrides k)]))
                              (into (set (keys old-overrides)) (keys overrides)))
        resumed? (some? (attr-get run :harness/resumes))]
    (merge overlay-delta
           {:harness/alias requested
            :harness/harness concrete
            :harness/env env
            :harness/cwd cwd
            :harness/status "ready"
            :harness/substatus "pending"
            :harness/settled nil
            :harness/settlement nil
            :harness/invocation nil
            :harness/generated generated-delta
            :harness/overrides overrides-delta
            :harness/session-id (if resumed?
                                  (attr-get run :harness/session-id)
                                  (str (java.util.UUID/randomUUID)))
            :harness/error nil
            :harness/result nil
            :harness/exit-code nil})))

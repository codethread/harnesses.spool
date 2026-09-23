(ns ct.spools.harnesses.internal.runs
  "Durable run publication, reservation, and continuation helpers."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.managed-startup :as managed]
            [ct.spools.harnesses.internal.registry :as registry]
            [ct.spools.harnesses.internal.publication :as publication]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn bind-invocation-markers
  "Replace assignment markers with the current published invocation values."
  [value run-id identity-id]
  (cond
    (string? value) (cond-> (str/replace value "{{RUN_ID}}" run-id)
                      identity-id (str/replace "{{AGENT_ID}}" identity-id))
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
    (guidance/validation-run rt run)))

(defn runs-where
  "List harness runs matching additional query `clauses`."
  [rt clauses]
  (mapv #(guidance/validation-run rt %)
        (weaver/list rt (into [:and [:= [:attr "harness/run"] "true"]]
                              clauses)
                     {})))

(defn reserving-session-writers
  "Return runs that still reserve `session-id`."
  [rt session-id]
  (filterv life/reserving?
           (runs-where rt [[:= [:attr "harness/session-id"] session-id]])))

(defn reserving-target-runs
  "Return runs that still reserve `target`."
  [rt target]
  (filterv life/reserving?
           (runs-where rt [[:= [:attr "harness/target"] target]])))

(defn request-holder
  "Return the unique run holding `request-id`, or nil when the key is free."
  [rt request-id]
  (when request-id
    (let [matches (runs-where rt [[:= [:attr "harness/request-id"] request-id]])]
      (when (next matches)
        (fail! "Request id is held by multiple harness runs"
               {:request-id request-id :runs (mapv :id matches)}))
      (first matches))))

(defn request-match
  "Return the run already holding `request-id`, or nil when the key is free.

  An equivalent repeat converges on the original run. A different request
  under the same key is a caller bug and fails with the conflicting run's
  ID rather than launching a second agent."
  [rt request-id fingerprint]
  (when-let [existing (request-holder rt request-id)]
    (when-not (= fingerprint (attr-get existing :harness/request-fingerprint))
      (fail! "Request id is already held by a different harness request"
             {:request-id request-id :run (:id existing)}))
    (publication/recover! rt (:id existing))))

(defn continuation-child
  "Return an accepted continuation of `run-id`, if one exists."
  [rt run-id]
  (require-run rt run-id)
  (let [child-ids (set (concat
                        (map :from_strand_id
                             (graph/incoming-edges rt [run-id] "resumes"))
                        (map :from_strand_id
                             (graph/incoming-edges rt [run-id] "continues"))))]
    (some #(let [child (some->> (weaver/show rt %)
                                (guidance/validation-run rt))]
             (when (and child (life/accepted? child)) child))
          child-ids)))

(defn require-continuation-head!
  "Reject a predecessor that already has an accepted continuation."
  [rt run-id]
  (when-let [child (continuation-child rt run-id)]
    (fail! "Harness predecessor already has an accepted continuation"
           {:predecessor run-id :continuation (:id child)}))
  (let [run (require-run rt run-id)]
    (when-not (life/accepted? run)
      (fail! "Harness predecessor publication was not accepted" {:id run-id}))
    run))

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
  [rt {:keys [title alias harness mode generated env overrides effective
              literal-extra-argv cwd session-id requested-session-id prompt
              resumes after target root-targets context request-id fingerprint
              logical-id by-identity resume-selector-intent guidance-selection
              guidance-context-template]
       :as request}]
  (when resumes
    (let [predecessor (require-run rt resumes)
          requested-session-id
          (if (contains? request :requested-session-id)
            requested-session-id
            session-id)]
      (when (= "codex" (attr-get predecessor :harness/harness))
        (when-not (and (= harness "codex")
                       (= requested-session-id (attr-get predecessor :harness/session-id))
                       (= "native-startup" (attr-get predecessor :harness/native-attachment-source)))
          (fail! "Codex continuation requires its registered native provider and session"
                 {:predecessor resumes})))
      (when (= "pi" (attr-get predecessor :harness/harness))
        (when-not (and (= "pi" harness)
                       (= requested-session-id (attr-get predecessor :harness/session-id))
                       (= "true" (attr-get predecessor :harness/native-attached)))
          (fail! "Pi continuation requires its attached native session"
                 {:predecessor resumes})))
      (require-continuation-head! rt resumes)))
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
                         :harness/publication-phase "created"
                         :harness/publication-outcome "publishing"
                         :harness/cwd cwd
                         :harness/session-id session-id
                         :harness/env env
                         :harness/generated generated
                         :harness/overrides overrides}
                        effective
                        (when (some? literal-extra-argv)
                          {:harness.internal/literal-extra-argv "true"})
                        (when-not (str/blank? prompt)
                          {:harness/prompt prompt
                           :harness/prompt-template prompt})
                        (when context
                          {:harness/context-template context})
                        (when by-identity
                          {:identity/by-identity by-identity})
                        (when resumes {:harness/resumes resumes})
                        (when after {:harness/after after})
                        (when target {:harness/target target})
                        (when (some? root-targets)
                          {:harness/root-targets root-targets})
                        (when context {:harness/context context})
                        (when request-id
                          {:harness/request-id request-id
                           :harness/request-fingerprint fingerprint})
                        (when resume-selector-intent
                          {:harness/resume-selector-intent
                           resume-selector-intent}))}
                (or resumes after target)
                (assoc :edges (cond-> []
                                resumes (conj {:type "resumes" :to resumes})
                                after (conj {:type "continues" :to after})
                                target (conj {:type "serves" :to target})
                                (seq root-targets)
                                (into (for [root-target root-targets]
                                        {:type "serves-root"
                                         :to root-target}))))))
             "create! produced an invalid run strand")]
    (try
      (publication/check-interrupted!)
      (let [predecessor (when resumes (require-run rt resumes))
            identity-binding (when-not (= "pi" harness)
                               (managed/commit-identity!
                                rt {:harness harness
                                    :session-id session-id
                                    :run run
                                    :predecessor predecessor
                                    :by-identity by-identity
                                    :effective effective}))
            run-id (:id run)
            identity-id (:identity identity-binding)
            _ (weaver/update!
               rt run-id
               {:attributes {:identity/id identity-id
                             :identity/prompt (:prompt identity-binding)
                             :harness/publication-phase "bound"
                             :harness/native-attached (when (= "codex" harness) "false")}})
            _ (publication/check-interrupted!)
            guidance-patch
            (guidance/publication-patch
             rt run-id identity-id (:prompt identity-binding)
             (:harness/appended-system-prompts effective)
             guidance-selection guidance-context-template [])
            effective (bind-invocation-markers effective run-id identity-id)
            effective (cond-> effective
                        (some? literal-extra-argv)
                        (assoc :harness/extra-argv literal-extra-argv))
            prompt (bind-invocation-markers prompt run-id identity-id)
            context (bind-invocation-markers context run-id identity-id)
            published (guidance/validation-run
                       rt
                       (require-valid!
                        :ct.spools.harnesses/strand
                        (weaver/update!
                         rt (:id run)
                         {:attributes (merge effective
                                             guidance-patch
                                             (when (some? prompt) {:harness/prompt prompt})
                                             (when context {:harness/context context})
                                             {:harness/logical-id (or logical-id (:id run))
                                              ;; Binding is published, but assignment enrichment
                                              ;; and the final commit still fence scheduling.
                                              :harness/published "true"
                                              :harness/publication-phase "published"})})
                        "create! produced an invalid published run"))]
        (publication/check-interrupted!)
        (when publication/*enrich*
          (publication/*enrich* rt published))
        (publication/complete! rt published (or resumes after)))
      (catch Throwable error
        (publication/fail! rt (:id run) error)))))

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
          (mapv #(guidance/validation-run rt %)
                (weaver/list rt
                             [:and
                              [:= [:attr "harness/run"] "true"]
                              [:= [:attr "harness/published"] "true"]
                              [:or
                               [:= [:attr "harness/status"] "running"]
                               [:and
                                [:in [:attr "harness/status"] ["stopped" "failed"]]
                                [:not [:= [:attr "harness/settled"] "true"]]]]]
                             {}))))

(defn accepted-lineage
  "Return every accepted run matching `attribute` = `value`."
  [rt attribute value]
  (filterv #(and (life/accepted? %)
                 (= value (attr-get % attribute)))
           (runs-where rt [])))

(defn resolve-lineage-head
  "Return the latest accepted head for `attribute` = `value`.

  Every accepted child is inspected so a still-running continuation keeps
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
  [run {:keys [requested concrete env generated overrides effective cwd
               session-id identity-binding]}]
  (let [old-attrs (:attributes run)
        old-generated (registry/normalize-overlay (attr-get run :harness/generated))
        old-overrides (registry/normalize-overlay (attr-get run :harness/overrides))
        identity-id (when-not (= "codex" concrete)
                      (or (:identity identity-binding)
                          (attr-get run :identity/id)))
        literal-extra-argv?
        (= "true" (attr-get run :harness.internal/literal-extra-argv))
        literal-extra-argv (when literal-extra-argv?
                             (attr-get run :harness/extra-argv))
        effective (bind-invocation-markers
                   (cond-> effective
                     literal-extra-argv? (dissoc :harness/extra-argv))
                   (:id run)
                   identity-id)
        effective (cond-> effective
                    literal-extra-argv?
                    (assoc :harness/extra-argv literal-extra-argv))
        old-overlay-keys (set (filter registry/overlay-key? (keys old-attrs)))
        all-overlay-keys (into old-overlay-keys (keys effective))
        overlay-delta (into {} (map (fn [k] [k (get effective k)]) all-overlay-keys))
        generated-delta (into {}
                              (map (fn [k] [k (get generated k)]))
                              (into (set (keys old-generated)) (keys generated)))
        overrides-delta (into {}
                              (map (fn [k] [k (get overrides k)]))
                              (into (set (keys old-overrides)) (keys overrides)))
        prompt-template (attr-get run :harness/prompt-template)
        context-template (attr-get run :harness/context-template)]
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
            :harness/session-id session-id
            :harness/session-usable nil
            :harness/stop-requested-at nil
            :harness/stop-reason nil
            :harness/error nil
            :harness/result nil
            :harness/exit-code nil
            :harness/observed-model nil
            :harness/observed-effort nil
            :harness/native-attached-at nil
            :harness/native-attachment-source nil
            :harness/native-attachment-attempt nil
            :harness/native-attachment-invocation nil}
           (when (some? prompt-template)
             {:harness/prompt
              (bind-invocation-markers prompt-template (:id run) identity-id)})
           (when (some? context-template)
             {:harness/context
              (bind-invocation-markers context-template (:id run) identity-id)})
           (when (= "pi" concrete)
             {:identity/id nil
              :identity/prompt nil
              :harness/native-attached nil})
           (when identity-binding
             {:identity/id identity-id
              :identity/prompt (:prompt identity-binding)
              :harness/native-attached
              (if (:native-attached identity-binding) "true" "false")}))))

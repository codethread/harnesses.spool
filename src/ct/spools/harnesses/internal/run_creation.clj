(ns ct.spools.harnesses.internal.run-creation
  "Publication planning and commit orchestration for new harness runs."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.catalog :as catalog]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.lifecycle :as life]
            [ct.spools.harnesses.internal.registry :as registry]
            [ct.spools.harnesses.internal.runs :as runs]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.util UUID]))

(defn fingerprint
  "Return the stable idempotency fingerprint for one create request."
  [request]
  (life/fingerprint (dissoc request :request-id)))

(defn prepare-publication
  "Resolve and admit one request into immutable publication data.

  The caller must hold the catalog publication lock through the subsequent
  commit so uniqueness checks and publication remain one transaction."
  [rt request guidance-context-template request-fingerprint]
  (let [{:keys [harness mode prompt cwd attributes title resumes after session-id
                append-system-prompt literal-extra-argv by-identity target
                root-targets context request-id logical-id frozen
                guidance-transport resume-selector-intent]} request
        mode (registry/mode-keyword (or mode :headless))
        {:keys [alias harness definition generated env]}
        (if frozen
          (runs/frozen-resolution rt frozen catalog/concrete-harness)
          (catalog/resolve-harness rt harness))
        overrides (cond-> (registry/normalize-overlay attributes)
                    append-system-prompt
                    (update registry/appended-system-prompts-attribute
                            (fnil conj []) append-system-prompt)
                    (some? literal-extra-argv)
                    (assoc :harness/extra-argv literal-extra-argv))
        effective (registry/merge-overlays generated overrides)
        effective (if guidance-context-template
                    (assoc effective
                           :harness/appended-system-prompts
                           (or (get guidance-context-template
                                    "appended-system-prompts")
                               (get guidance-context-template
                                    :appended-system-prompts)))
                    effective)
        cwd (or cwd (System/getProperty "user.dir"))
        requested-session-id session-id
        session-id (or session-id (str (UUID/randomUUID)))
        guidance-selection
        (guidance/select!
         rt {:harness harness
             :requested guidance-transport
             :mode mode
             :cwd cwd
             :env env
             :effective effective
             :session-id session-id
             :resumes resumes})]
    (when-not (contains? (:modes definition) mode)
      (fail! "Harness does not support requested mode"
             {:harness harness :mode mode :modes (:modes definition)}))
    (when (and (= :headless mode) (str/blank? prompt))
      (fail! "Headless harness run requires a prompt" {:harness alias}))
    (when-let [writers (seq (runs/reserving-session-writers rt session-id))]
      (fail! "Native session already has an active managed writer"
             {:session-id session-id :runs (mapv :id writers)}))
    (when target
      (when-let [serving (seq (runs/reserving-target-runs rt target))]
        (fail! "Target already has an active managed run"
               {:target target :runs (mapv :id serving)})))
    {:title (or title (registry/run-title alias mode prompt))
     :alias alias :harness harness :mode mode :definition definition
     :generated generated :env env :overrides overrides
     :effective effective :literal-extra-argv literal-extra-argv
     :cwd cwd :session-id session-id
     :requested-session-id requested-session-id
     :prompt prompt :resumes resumes :after after :target target
     :root-targets root-targets :context context :request-id request-id
     :fingerprint request-fingerprint
     :logical-id logical-id :by-identity by-identity
     :resume-selector-intent resume-selector-intent
     :guidance-selection guidance-selection
     :guidance-context-template guidance-context-template}))

(defn commit-publication!
  "Commit one fully admitted publication plan in invariant write order."
  [rt publication]
  (runs/commit-run! rt publication))

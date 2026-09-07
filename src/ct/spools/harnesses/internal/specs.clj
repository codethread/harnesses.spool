(ns ct.spools.harnesses.internal.specs
  "Public harness data specs, registered under `ct.spools.harnesses`.

  Kept here so the public namespace can stay a readable story under the
  module-size limit. Callers still write `:ct.spools.harnesses/...`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.registry :as registry]))

(s/def :ct.spools.harnesses/runtime map?)
(s/def :ct.spools.harnesses/name-ref
  #(or (keyword? %) (symbol? %) (and (string? %) (not (str/blank? %)))))
(s/def :ct.spools.harnesses/id (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/title (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/state #{"active" "closed"})
(s/def :ct.spools.harnesses/attributes map?)
(s/def :ct.spools.harnesses/doc (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/effort
  #(or (keyword? %)
       (and (string? %) (not (str/blank? %)))))
(s/def :ct.spools.harnesses/model (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/append-system-prompt
  (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/appended-system-prompts
  (s/coll-of :ct.spools.harnesses/append-system-prompt :kind vector?))
(s/def :ct.spools.harnesses/strand
  (s/keys :req-un [:ct.spools.harnesses/id :ct.spools.harnesses/title
                   :ct.spools.harnesses/state :ct.spools.harnesses/attributes]))
(s/def :ct.spools.harnesses/mode #{:headless :interactive "headless" "interactive"})
(s/def :ct.spools.harnesses/modes
  (s/coll-of #{:headless :interactive} :kind set? :min-count 1))
(s/def :ct.spools.harnesses/callback qualified-symbol?)
(s/def :ct.spools.harnesses/prepare :ct.spools.harnesses/callback)
(s/def :ct.spools.harnesses/finish :ct.spools.harnesses/callback)
(s/def :ct.spools.harnesses/overlay-attributes
  (s/and map?
         #(every? registry/overlay-key? (keys %))
         #(every? registry/json-value? (vals %))))
(s/def :ct.spools.harnesses/harness-definition
  (s/and
   (s/keys :req-un [:ct.spools.harnesses/modes
                    :ct.spools.harnesses/prepare
                    :ct.spools.harnesses/finish]
           :opt-un [:ct.spools.harnesses/attributes])
   #(qualified-symbol? (:prepare %))
   #(qualified-symbol? (:finish %))
   #(every? #{:modes :prepare :finish :attributes} (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :ct.spools.harnesses/overlay-attributes (:attributes %)))))
(s/def :ct.spools.harnesses/condition
  #(or (s/valid? :ct.spools.harnesses/name-ref %)
       (and (vector? %)
            (cond
              (= :not (first %))
              (and (= 2 (count %)) (s/valid? :ct.spools.harnesses/condition (second %)))

              (#{:and :or} (first %))
              (and (< 1 (count %))
                   (every? (partial s/valid? :ct.spools.harnesses/condition) (rest %)))

              :else false))))
(s/def :ct.spools.harnesses/when :ct.spools.harnesses/condition)
(s/def :ct.spools.harnesses/visibility-names
  (s/and set? #(every? (partial s/valid? :ct.spools.harnesses/name-ref) %)))
(s/def :ct.spools.harnesses/allow :ct.spools.harnesses/visibility-names)
(s/def :ct.spools.harnesses/deny :ct.spools.harnesses/visibility-names)
(s/def :ct.spools.harnesses/env
  (s/map-of (s/and string? #(re-matches #"[A-Za-z_][A-Za-z0-9_]*" %))
            string?))
(s/def :ct.spools.harnesses/alias-candidate
  (s/and (s/keys :req-un [:ct.spools.harnesses/doc
                          :ct.spools.harnesses/parent
                          :ct.spools.harnesses/attributes]
                 :opt-un [:ct.spools.harnesses/when
                          :ct.spools.harnesses/append-system-prompt
                          :ct.spools.harnesses/allow
                          :ct.spools.harnesses/deny
                          :ct.spools.harnesses/env])
         #(every? #{:doc :parent :model :effort :append-system-prompt
                    :attributes :when :allow :deny :env}
                  (keys %))
         #(not (and (contains? % :allow) (contains? % :deny)))
         #(s/valid? :ct.spools.harnesses/name-ref (:parent %))
         #(or (not (contains? % :model)) (s/valid? :ct.spools.harnesses/model (:model %)))
         #(or (not (contains? % :effort)) (s/valid? :ct.spools.harnesses/effort (:effort %)))
         #(s/valid? :ct.spools.harnesses/overlay-attributes (:attributes %))))
(s/def :ct.spools.harnesses/alias-descriptor
  #(or (s/valid? :ct.spools.harnesses/alias-candidate %)
       (and (vector? %) (seq %)
            (every? (partial s/valid? :ct.spools.harnesses/alias-candidate) %))))
(s/def :ct.spools.harnesses/candidates
  (s/coll-of :ct.spools.harnesses/alias-candidate :kind vector? :min-count 1))
(s/def :ct.spools.harnesses/alias-result
  (s/keys :req-un [:ct.spools.harnesses/alias :ct.spools.harnesses/candidates]))
(s/def :ct.spools.harnesses/alias string?)
(s/def :ct.spools.harnesses/parent :ct.spools.harnesses/name-ref)
(s/def :ct.spools.harnesses/harness :ct.spools.harnesses/name-ref)
(s/def :ct.spools.harnesses/definition :ct.spools.harnesses/harness-definition)
(s/def :ct.spools.harnesses/generated :ct.spools.harnesses/overlay-attributes)
(s/def :ct.spools.harnesses/resolved-harness
  (s/keys :req-un [:ct.spools.harnesses/alias
                   :ct.spools.harnesses/harness
                   :ct.spools.harnesses/definition
                   :ct.spools.harnesses/generated
                   :ct.spools.harnesses/env]))
(s/def :ct.spools.harnesses/name string?)
(s/def :ct.spools.harnesses/kind #{"harness" "alias"})
(s/def :ct.spools.harnesses/alias-of string?)
(s/def :ct.spools.harnesses/mode-name #{"headless" "interactive"})
(s/def :ct.spools.harnesses.registry/modes
  (s/coll-of :ct.spools.harnesses/mode-name :kind vector? :min-count 1))
(s/def :ct.spools.harnesses/available boolean?)
(s/def :ct.spools.harnesses/unavailable-reasons (s/coll-of map? :kind vector?))
(s/def :ct.spools.harnesses/selected-candidate nat-int?)
(s/def :ct.spools.harnesses/selected-parent string?)
(s/def :ct.spools.harnesses/registry-entry
  (s/or :harness
        (s/and (s/keys :req-un [:ct.spools.harnesses/name
                                :ct.spools.harnesses/kind
                                :ct.spools.harnesses.registry/modes
                                :ct.spools.harnesses/available]
                       :opt-un [:ct.spools.harnesses/harness
                                :ct.spools.harnesses/unavailable-reasons])
               #(= "harness" (:kind %))
               #(every? #{:name :kind :modes :available :harness
                          :unavailable-reasons}
                        (keys %)))
        :alias
        (s/and (s/keys :req-un [:ct.spools.harnesses/name
                                :ct.spools.harnesses/kind
                                :ct.spools.harnesses/candidates
                                :ct.spools.harnesses/available]
                       :opt-un [:ct.spools.harnesses/harness
                                :ct.spools.harnesses/selected-candidate
                                :ct.spools.harnesses/selected-parent
                                :ct.spools.harnesses/unavailable-reasons])
               #(= "alias" (:kind %))
               #(every? #{:name :kind :candidates :available :harness
                          :selected-candidate :selected-parent
                          :unavailable-reasons}
                        (keys %)))))
(s/def :ct.spools.harnesses/registry-list
  (s/coll-of :ct.spools.harnesses/registry-entry :kind vector?))
(s/def :ct.spools.harnesses/harness-registration
  (s/keys :req-un [:ct.spools.harnesses/harness :ct.spools.harnesses/definition]))
(s/def :ct.spools.harnesses/prompt string?)
(s/def :ct.spools.harnesses/argv
  (s/coll-of (s/and string? (complement str/blank?))
             :kind vector? :min-count 1))
(s/def :ct.spools.harnesses/stdin (s/nilable string?))
(s/def :ct.spools.harnesses/launch-spec
  (s/and
   (s/keys :req-un [:ct.spools.harnesses/argv :ct.spools.harnesses/stdin]
           :opt-un [:ct.spools.harnesses/env])
   #(every? #{:argv :stdin :env} (keys %))))
(s/def :ct.spools.harnesses/cwd (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/session-id (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/resumes :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/after :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/by-identity :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/target :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/root-targets
  (s/coll-of :ct.spools.harnesses/id :kind vector? :distinct true))
(s/def :ct.spools.harnesses/context (s/and map? registry/json-value?))
(s/def :ct.spools.harnesses/request-id (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/logical-id :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/frozen map?)
(def create-keys
  "Closed key set accepted by `create!`."
  #{:harness :mode :prompt :cwd :attributes :title :resumes :after :session-id
    :append-system-prompt :by-identity :target :root-targets :context :request-id
    :logical-id :frozen})
(s/def :ct.spools.harnesses/create-request
  (s/and
   (s/keys :req-un [:ct.spools.harnesses/harness]
           :opt-un [:ct.spools.harnesses/mode :ct.spools.harnesses/prompt
                    :ct.spools.harnesses/cwd :ct.spools.harnesses/attributes
                    :ct.spools.harnesses/title :ct.spools.harnesses/resumes
                    :ct.spools.harnesses/after
                    :ct.spools.harnesses/session-id
                    :ct.spools.harnesses/append-system-prompt
                    :ct.spools.harnesses/by-identity
                    :ct.spools.harnesses/target :ct.spools.harnesses/root-targets
                    :ct.spools.harnesses/context
                    :ct.spools.harnesses/request-id
                    :ct.spools.harnesses/logical-id
                    :ct.spools.harnesses/frozen])
   #(every? create-keys (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :ct.spools.harnesses/overlay-attributes (:attributes %)))))
(s/def :ct.spools.harnesses/status #{:done :failed "done" "failed"})
(s/def :ct.spools.harnesses/exit-code (s/nilable int?))
(s/def :ct.spools.harnesses/result (s/nilable string?))
(s/def :ct.spools.harnesses/error (s/nilable string?))
(s/def :ct.spools.harnesses/session-usable boolean?)
(s/def :ct.spools.harnesses/invocation :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/settled boolean?)
(s/def :ct.spools.harnesses/settlement (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/gap string?)
(s/def :ct.spools.harnesses/failure-class #{"launch" "execution" "reconciliation"})
(s/def :ct.spools.harnesses/cancelled? boolean?)
(s/def :ct.spools.harnesses/evidence
  (s/keys :req-un [:ct.spools.harnesses/settled :ct.spools.harnesses/settlement]
          :opt-un [:ct.spools.harnesses/gap :ct.spools.harnesses/failure-class
                   :ct.spools.harnesses/cancelled?]))
(s/def :ct.spools.harnesses/outcome
  (s/and
   (s/keys :req-un [:ct.spools.harnesses/status]
           :opt-un [:ct.spools.harnesses/exit-code
                    :ct.spools.harnesses/result
                    :ct.spools.harnesses/session-id
                    :ct.spools.harnesses/error
                    :ct.spools.harnesses/session-usable
                    :ct.spools.harnesses/invocation
                    :ct.spools.harnesses/evidence])
   #(every? #{:status :exit-code :result :session-id :error :session-usable
              :invocation :evidence}
            (keys %))))
(s/def :ct.spools.harnesses/reason (s/and string? (complement str/blank?)))
(s/def :ct.spools.harnesses/stop-request
  (s/and (s/keys :opt-un [:ct.spools.harnesses/reason])
         #(every? #{:reason} (keys %))))
(s/def :ct.spools.harnesses/attempt pos-int?)
(s/def :ct.spools.harnesses/started
  (s/keys :req-un [:ct.spools.harnesses/strand
                   :ct.spools.harnesses/invocation
                   :ct.spools.harnesses/attempt]))
(s/def :ct.spools.harnesses/eligible? boolean?)
(s/def :ct.spools.harnesses/resume-eligibility
  (s/keys :req-un [:ct.spools.harnesses/eligible? :ct.spools.harnesses/reason]))
(s/def :ct.spools.harnesses/retry-request
  (s/and
   (s/keys :opt-un [:ct.spools.harnesses/harness
                    :ct.spools.harnesses/cwd
                    :ct.spools.harnesses/attributes])
   #(every? #{:harness :cwd :attributes} (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :ct.spools.harnesses/overlay-attributes (:attributes %)))))
(s/def :ct.spools.harnesses/run-id :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/identity :ct.spools.harnesses/id)
(s/def :ct.spools.harnesses/resume-selector
  (s/and
   (s/keys :opt-un [:ct.spools.harnesses/run-id
                    :ct.spools.harnesses/session-id
                    :ct.spools.harnesses/identity
                    :ct.spools.harnesses/logical-id])
   #(= 1 (count (select-keys % [:run-id :session-id :identity
                                :logical-id])))
   #(every? #{:run-id :session-id :identity :logical-id} (keys %))))
(s/def :ct.spools.harnesses/resume-request
  (s/and
   (s/keys :opt-un [:ct.spools.harnesses/prompt
                    :ct.spools.harnesses/cwd
                    :ct.spools.harnesses/attributes
                    :ct.spools.harnesses/mode
                    :ct.spools.harnesses/title
                    :ct.spools.harnesses/by-identity
                    :ct.spools.harnesses/target
                    :ct.spools.harnesses/context
                    :ct.spools.harnesses/request-id])
   #(every? #{:prompt :cwd :attributes :mode :title :by-identity :target
              :context :request-id}
            (keys %))
   #(or (not (contains? % :attributes))
        (s/valid? :ct.spools.harnesses/overlay-attributes (:attributes %)))))

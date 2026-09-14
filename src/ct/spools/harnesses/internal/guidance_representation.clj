(ns ct.spools.harnesses.internal.guidance-representation
  "Closed durable discriminator for managed-guidance run metadata."
  (:require [clojure.string :as str]
            [millstrand.api.spool.alpha :as spool]))

(def attribute-keys
  "Every durable attribute participating in guidance representation choice."
  [:harness/guidance-version
   :harness/guidance-transport
   :harness/guidance-capability
   :harness/guidance-capability-sha256
   :harness/guidance-context-template
   :harness/guidance-context
   :harness/guidance-bundle-sha256
   :harness/guidance-attempts])

(def ^:private attempt-states
  #{"pending" "fetched" "acknowledged" "completed" "failed" "not-required"})

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn- string-key [key]
  (if (or (string? key) (instance? clojure.lang.Named key))
    (name key)
    key))

(defn- normalize-attempt [record]
  (if-not (map? record)
    record
    (into {}
          (map (fn [[key value]]
                 (let [normalized-key (string-key key)]
                   [normalized-key
                    (if (and (= "failure" normalized-key) (map? value))
                      (into {} (map (fn [[failure-key failure-value]]
                                      [(string-key failure-key) failure-value]))
                            value)
                      value)])))
          record)))

(defn- valid-attempt? [record]
  (and (map? record)
       (pos-int? (get record "attempt"))
       (string? (get record "invocation"))
       (contains? attempt-states (get record "state"))))

(defn- valid-attempts? [attempts]
  (and (vector? attempts)
       (every? valid-attempt? attempts)
       (let [numbers (mapv #(get % "attempt") attempts)]
         (and (= numbers (vec (sort numbers)))
              (= (count numbers) (count (distinct numbers)))))))

(defn- values [run]
  (into {} (map (fn [key] [key (spool/attr-get run key)])) attribute-keys))

(defn- present-keys [run]
  (let [attributes (:attributes run)]
    (into #{} (filter #(contains? attributes %)) attribute-keys)))

(defn- corrupt! [run representation present reason]
  (let [missing (->> attribute-keys
                     (remove present)
                     vec)]
    (spool/fail! "Harness run has corrupt partial guidance metadata"
                 {:run-id (:id run)
                  :version (:harness/guidance-version representation)
                  :transport (:harness/guidance-transport representation)
                  :missing missing
                  :reason reason})))

(defn validate!
  "Discriminate and validate one complete durable guidance representation.

  Wholly absent metadata is historical unversioned legacy. Once any guidance
  attribute is present, version, transport, frozen contexts, bundle digest, and
  ordered attempts are mandatory. Native metadata additionally requires both
  capability fields; legacy metadata forbids them."
  [run]
  (let [representation (values run)
        present (present-keys run)]
    (if (empty? present)
      {:versioned? false :transport "legacy" :attempts []}
      (let [{version :harness/guidance-version
             transport :harness/guidance-transport
             capability :harness/guidance-capability
             capability-sha :harness/guidance-capability-sha256
             template :harness/guidance-context-template
             context :harness/guidance-context
             bundle-sha :harness/guidance-bundle-sha256
             raw-attempts :harness/guidance-attempts} representation
            attempts (when (vector? raw-attempts)
                       (mapv normalize-attempt raw-attempts))
            required #{:harness/guidance-version
                       :harness/guidance-transport
                       :harness/guidance-context-template
                       :harness/guidance-context
                       :harness/guidance-bundle-sha256
                       :harness/guidance-attempts}
            common-valid?
            (and (every? present required)
                 (integer? version)
                 (= 1 version)
                 (contains? #{"legacy" "native-v1"} transport)
                 (map? template)
                 (map? context)
                 (nonblank? bundle-sha)
                 (valid-attempts? attempts))
            transport-valid?
            (case transport
              "legacy" (and (not (contains? present
                                            :harness/guidance-capability))
                            (not (contains?
                                  present
                                  :harness/guidance-capability-sha256)))
              "native-v1" (and (contains? present
                                          :harness/guidance-capability)
                               (contains?
                                present
                                :harness/guidance-capability-sha256)
                               (map? capability)
                               (nonblank? capability-sha))
              false)]
        (when-not (and common-valid? transport-valid?)
          (corrupt! run representation present
                    "incomplete-or-mixed-representation"))
        {:versioned? true
         :transport transport
         :attempts attempts}))))

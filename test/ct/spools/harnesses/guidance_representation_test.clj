(ns ct.spools.harnesses.guidance-representation-test
  "Closed guidance representation and no-write start regressions."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-representation :as representation]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.test.alpha :as test-alpha]))

(defn- failure [operation]
  (try
    (operation)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- common-attributes [harness transport]
  {:harness/guidance-version 1
   :harness/guidance-transport transport
   :harness/guidance-context-template {}
   :harness/guidance-context {}
   :harness/guidance-bundle-sha256 "bundle"
   :harness/guidance-attempts []
   :harness/harness harness
   :harness/mode "headless"
   :harness/cwd "/tmp"
   :harness/env {}})

(defn- representation-run [harness transport]
  (let [capability {"harness" harness}
        attributes (common-attributes harness transport)]
    {:id (str harness "-" transport)
     :attributes
     (cond-> attributes
       (= "native-v1" transport)
       (assoc :harness/guidance-capability capability
              :harness/guidance-capability-sha256
              (strict-json/canonical-sha256 capability)))}))

(defn- corrupt? [run]
  (let [error (failure #(guidance/validate-representation! run))]
    (boolean
     (and error
          (re-find #"corrupt partial guidance metadata"
                   (ex-message error))))))

(deftest shared-discriminator-closes-every-guidance-representation
  (testing "wholly absent historical metadata is the only unversioned form"
    (is (= {:versioned? false :transport "legacy" :attempts []}
           (guidance/validate-representation!
            {:id "historical" :attributes {}})))
    (is (corrupt?
         {:id "present-nils"
          :attributes (zipmap representation/attribute-keys (repeat nil))})))
  (doseq [harness ["codex" "pi"]]
    (testing (str harness " valid legacy is explicitly not required")
      (let [run (representation-run harness "legacy")
            patch (guidance/begin-attempt-patch nil run 1 "legacy-invocation")]
        (is (= "legacy" (guidance/transport run)))
        (is (= "not-required"
               (get-in patch [:harness/guidance-attempts 0 "state"])))))
    (testing (str harness " valid native is pending")
      (let [run (representation-run harness "native-v1")
            capability (get-in run [:attributes :harness/guidance-capability])
            digest (get-in run
                           [:attributes :harness/guidance-capability-sha256])]
        (with-redefs
         [guidance/select!
          (fn [_ _]
            {:transport "native-v1"
             :capability capability
             :capability-sha256 digest
             :launch-plan {:harness harness}})]
          (let [patch (guidance/begin-attempt-patch
                       nil run 1 "native-invocation")]
            (is (= "native-v1" (guidance/transport run)))
            (is (= "pending"
                   (get-in patch [:harness/guidance-attempts 0 "state"])))))))
    (testing (str harness " rejects partial common fields")
      (let [legacy (representation-run harness "legacy")]
        (is (corrupt? (update legacy :attributes dissoc
                              :harness/guidance-version)))
        (doseq [key [:harness/guidance-transport
                     :harness/guidance-context-template
                     :harness/guidance-context
                     :harness/guidance-bundle-sha256
                     :harness/guidance-attempts]]
          (is (corrupt? (update legacy :attributes dissoc key))))
        (doseq [version [0 2 "1"]]
          (is (corrupt? (assoc-in legacy
                                  [:attributes :harness/guidance-version]
                                  version))))
        (doseq [transport ["native" :legacy]]
          (is (corrupt? (assoc-in legacy
                                  [:attributes :harness/guidance-transport]
                                  transport))))
        (doseq [attempts [[{}]
                          [{"attempt" 1 "invocation" "one"
                            "state" "unknown"}]
                          [{"attempt" 1 "invocation" "one"
                            "state" "pending"}
                           {"attempt" 1 "invocation" "two"
                            "state" "failed"}]
                          [{"attempt" 2 "invocation" "two"
                            "state" "failed"}
                           {"attempt" 1 "invocation" "one"
                            "state" "pending"}]]]
          (is (corrupt? (assoc-in legacy
                                  [:attributes :harness/guidance-attempts]
                                  attempts))))))
    (testing (str harness " rejects missing or mixed capability fields")
      (let [native (representation-run harness "native-v1")
            legacy (representation-run harness "legacy")]
        (doseq [key [:harness/guidance-capability
                     :harness/guidance-capability-sha256]]
          (is (corrupt? (update native :attributes dissoc key))))
        (is (corrupt?
             (assoc-in legacy [:attributes :harness/guidance-capability] {})))))))

(deftest corrupt-durable-rows-reject-before-all-start-side-effects
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(do
                 (require '[ct.spools.harnesses.providers.pi :as pi])
                 (harnesses/register-harness! rt :pi (pi/harness rt))
                 (let [capture-failure
                       (fn [operation]
                         (try
                           (operation)
                           nil
                           (catch clojure.lang.ExceptionInfo error error)))
                       create-legacy
                       (fn [harness suffix]
                         (harnesses/create!
                          rt {:harness harness
                              :mode :headless
                              :prompt (str "representation " suffix)
                              :cwd "/tmp"
                              :guidance-transport "legacy"}))
                       valid
                       (mapv (fn [harness]
                               (let [run (create-legacy harness "valid")
                                     started (harnesses/begin-attempt!
                                              rt (:id run))]
                                 (let [record
                                       (first
                                        (attr (:strand started)
                                              :harness/guidance-attempts))]
                                   (or (get record "state")
                                       (get record :state)))))
                             [:codex :pi])
                       historical (create-legacy :codex "historical")
                       _ (weaver/update!
                          rt (:id historical)
                          {:attributes
                           (zipmap
                            [:harness/guidance-version
                             :harness/guidance-transport
                             :harness/guidance-capability
                             :harness/guidance-capability-sha256
                             :harness/guidance-context-template
                             :harness/guidance-context
                             :harness/guidance-bundle-sha256
                             :harness/guidance-attempts]
                            (repeat nil))})
                       historical-start
                       (harnesses/begin-attempt! rt (:id historical))
                       corrupt
                       (mapv (fn [harness]
                               (let [run (create-legacy harness "corrupt")]
                                 (weaver/update!
                                  rt (:id run)
                                  {:attributes
                                   {:harness/guidance-version nil}})
                                 (weaver/show rt (:id run))))
                             [:codex :pi])
                       helper-calls (atom 0)
                       provider-calls (atom 0)
                       errors
                       (with-redefs
                        [capability/preflight!
                         (fn [& _] (swap! helper-calls inc))
                         codex/prepare
                         (fn [& _] (swap! provider-calls inc))
                         pi/prepare
                         (fn [& _] (swap! provider-calls inc))]
                         (mapv (fn [run]
                                 (capture-failure
                                  #(harnesses/begin-attempt! rt (:id run))))
                               corrupt))
                       after (mapv #(weaver/show rt (:id %)) corrupt)]
                   {:valid valid
                    :historical-status (attr (:strand historical-start)
                                             :harness/status)
                    :historical-attempts
                    (attr (:strand historical-start)
                          :harness/guidance-attempts)
                    :errors (mapv ex-message errors)
                    :unchanged (= corrupt after)
                    :attempts (mapv #(attr % :harness/attempt) after)
                    :invocations (mapv #(attr % :harness/invocation) after)
                    :helper-calls @helper-calls
                    :provider-calls @provider-calls}))))]
        (is (= ["not-required" "not-required"] (:valid result)))
        (is (= "running" (:historical-status result)))
        (is (nil? (:historical-attempts result)))
        (is (every? #(re-find #"corrupt partial guidance metadata" %)
                    (:errors result)))
        (is (true? (:unchanged result)))
        (is (= [nil nil] (:attempts result)))
        (is (= [nil nil] (:invocations result)))
        (is (zero? (:helper-calls result)))
        (is (zero? (:provider-calls result)))))))

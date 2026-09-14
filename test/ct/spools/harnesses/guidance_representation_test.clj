(ns ct.spools.harnesses.guidance-representation-test
  "Closed guidance representation and no-write start regressions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-context :as context]
            [ct.spools.harnesses.internal.guidance-representation :as representation]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.test.alpha :as test-alpha]))

(def ^:private workspace "/tmp")
(def ^:private identity-id "steady-fair-lynx")
(def ^:private sha-a (str/join (repeat 64 "a")))
(def ^:private sha-b (str/join (repeat 64 "b")))

(defn- failure [operation]
  (try
    (operation)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- hook-fact [harness]
  (if (= "codex" harness)
    {"eventName" "sessionStart"
     "key" "managed-guidance"
     "source" "plugin"
     "sourcePath" "/fixture/plugin/hooks.json"
     "pluginId" "agents-fixture"
     "command" "node managed-guidance.js"
     "enabled" true
     "trustStatus" "trusted"
     "currentHash" "trusted-current-hash"
     "timeoutSec" 15
     "additionalContextLimit" 4096}
    {"host-package" "@mariozechner/pi-coding-agent"
     "host-package-version" "0.84.4"
     "host-package-sha256" sha-a
     "extensions"
     [{"entrypoint" "/fixture/pi/managed-guidance.ts"
       "closure-sha256" sha-b}]
     "prompt-owner-entrypoint" "/fixture/pi/managed-guidance.ts"
     "system-prompt-options-contract" "owned-v1"}))

(defn- capability-document [harness]
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" harness
   "adapter-contract" "native-v1"
   "adapter-sha256" sha-a
   "executable-sha256" sha-b
   "host-version" (if (= "codex" harness) "0.154.0" "0.84.4")
   "launch-profile-sha256" sha-a
   "max-context-bytes" (if (= "codex" harness) 3072 65536)
   "hook-fact" (hook-fact harness)})

(defn- valid-run [harness transport]
  (let [run-id (str harness "-" transport)
        template {"schema" context/schema
                  "identity-instruction" "Use {{AGENT_ID}} for {{RUN_ID}}."
                  "appended-system-prompts" []}
        materialized (context/validate!
                      (context/bind-markers template run-id identity-id))
        capability (capability-document harness)
        attributes
        (cond-> {:harness/guidance-version 1
                 :harness/guidance-transport transport
                 :harness/guidance-context-template template
                 :harness/guidance-context materialized
                 :harness/guidance-bundle-sha256
                 (context/bundle-sha256 run-id workspace materialized)
                 :harness/guidance-attempts []
                 :harness/harness harness
                 :identity/id identity-id
                 :harness/mode "headless"
                 :harness/cwd workspace
                 :harness/env {}}
          (= "native-v1" transport)
          (assoc :harness/guidance-capability capability
                 :harness/guidance-capability-sha256
                 (strict-json/canonical-sha256 capability)))]
    (representation/attach-context {:id run-id :attributes attributes}
                                   workspace)))

(defn- corrupt? [run]
  (let [error (failure #(guidance/validate-representation! run))]
    (boolean
     (and error
          (re-find #"corrupt partial guidance metadata"
                   (ex-message error))))))

(defn- pending-attempt [number]
  {"attempt" number
   "invocation" (str "invocation-" number)
   "transport" "native-v1"
   "state" "pending"
   "started-at" "2026-09-14T00:00:00Z"
   "deadline-at" "2026-09-14T00:00:20Z"
   "bundle-sha256" sha-a
   "capability-sha256" sha-b})

(defn- legacy-attempt [number]
  {"attempt" number
   "invocation" (str "invocation-" number)
   "transport" "legacy"
   "state" "not-required"
   "started-at" "2026-09-14T00:00:00Z"})

(deftest complete-shared-discriminator-accepts-only-valid-representations
  (testing "wholly absent historical metadata remains legacy"
    (is (= {:versioned? false :transport "legacy" :attempts []}
           (guidance/validate-representation!
            {:id "historical" :attributes {}})))
    (is (corrupt?
         {:id "present-nils"
          :attributes (zipmap representation/attribute-keys (repeat nil))})))
  (doseq [harness ["codex" "pi"]]
    (testing (str harness " positive legacy and native starts")
      (let [legacy (valid-run harness "legacy")
            native (valid-run harness "native-v1")
            capability (get-in native
                               [:attributes :harness/guidance-capability])
            digest (get-in native
                           [:attributes :harness/guidance-capability-sha256])]
        (is (= "not-required"
               (get-in (guidance/begin-attempt-patch
                        nil legacy 1 "legacy-invocation")
                       [:harness/guidance-attempts 0 "state"])))
        (with-redefs
         [guidance/select!
          (fn [_ _]
            {:transport "native-v1"
             :capability capability
             :capability-sha256 digest
             :launch-plan {:harness harness}})]
          (is (= "pending"
                 (get-in (guidance/begin-attempt-patch
                          nil native 1 "native-invocation")
                         [:harness/guidance-attempts 0 "state"]))))))
    (testing (str harness " rejects malformed common representation")
      (let [legacy (valid-run harness "legacy")]
        (doseq [key [:harness/guidance-version
                     :harness/guidance-transport
                     :harness/guidance-context-template
                     :harness/guidance-context
                     :harness/guidance-bundle-sha256
                     :harness/guidance-attempts]]
          (is (corrupt? (update legacy :attributes dissoc key))))
        (doseq [[path value]
                [[[:harness/guidance-version] 2]
                 [[:harness/guidance-transport] "native"]
                 [[:harness/guidance-context-template] {}]
                 [[:harness/guidance-context "identity-instruction"] " "]
                 [[:harness/guidance-context "appended-system-prompts"] [""]]
                 [[:harness/guidance-bundle-sha256] sha-a]
                 [[:harness/guidance-attempts] {}]]]
          (is (corrupt? (assoc-in legacy (into [:attributes] path) value))))
        (is (corrupt?
             (assoc-in legacy
                       [:attributes :harness/guidance-context-template
                        :schema]
                       context/schema)))
        (is (corrupt?
             (assoc-in legacy
                       [:attributes :harness/guidance-context-template
                        "schema"]
                       "collision")))))
    (testing (str harness " rejects malformed native capability")
      (let [native (valid-run harness "native-v1")]
        (doseq [key [:harness/guidance-capability
                     :harness/guidance-capability-sha256]]
          (is (corrupt? (update native :attributes dissoc key))))
        (doseq [[path value]
                [[[:harness/guidance-capability "schema"] "v2"]
                 [[:harness/guidance-capability "harness"] "other"]
                 [[:harness/guidance-capability "adapter-sha256"] "ABC"]
                 [[:harness/guidance-capability "local-ownership"] {}]
                 [[:harness/guidance-capability-sha256] sha-a]]]
          (is (corrupt? (assoc-in native (into [:attributes] path) value))))
        (is (corrupt?
             (assoc-in native
                       [:attributes :harness/guidance-capability :schema]
                       "collision")))))
    (testing (str harness " validates every historical attempt transport")
      (let [legacy (valid-run harness "legacy")
            valid-history [(pending-attempt 1) (legacy-attempt 2)]]
        (is (= valid-history
               (:attempts
                (guidance/validate-representation!
                 (assoc-in legacy
                           [:attributes :harness/guidance-attempts]
                           valid-history)))))
        (doseq [attempts
                [[(assoc (legacy-attempt 1) "state" "pending")]
                 [(assoc (pending-attempt 1) "state" "completed")]
                 [(dissoc (pending-attempt 1) "deadline-at")]
                 [(assoc (pending-attempt 1) "started-at" "bad-time")]
                 [(assoc (pending-attempt 1) "invocation" "")]
                 [(pending-attempt 2) (legacy-attempt 1)]
                 [(pending-attempt 1) (legacy-attempt 1)]
                 [(assoc (pending-attempt 1) :state "collision")]
                 [(assoc (pending-attempt 1) "unknown" true)]]]
          (is (corrupt?
               (assoc-in legacy [:attributes :harness/guidance-attempts]
                         attempts))))))))

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
                         (try (operation) nil
                              (catch clojure.lang.ExceptionInfo error error)))
                       create-legacy
                       (fn [harness suffix]
                         (harnesses/create!
                          rt {:harness harness :mode :headless
                              :prompt (str "representation " suffix)
                              :cwd "/tmp" :guidance-transport "legacy"}))
                       valid (mapv #(harnesses/begin-attempt!
                                     rt (:id (create-legacy % "valid")))
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
                       corrupt-legacy
                       (mapv (fn [harness]
                               (let [run (create-legacy harness "corrupt")]
                                 (weaver/update!
                                  rt (:id run)
                                  {:attributes
                                   {:harness/guidance-version nil}})
                                 (weaver/show rt (:id run))))
                             [:codex :pi])
                       corrupt-native
                       (mapv
                        (fn [harness]
                          (let [run (create-legacy harness "native-corrupt")
                                capability-document
                                (if (= :codex harness)
                                  capability-document pi-capability-document)
                                selection
                                {:transport "native-v1"
                                 :capability capability-document
                                 :capability-sha256
                                 (strict-json/canonical-sha256
                                  capability-document)}
                                patch
                                (guidance/publication-patch
                                 rt (:id run) (attr run :identity/id)
                                 (attr run :identity/prompt)
                                 (attr run :harness/appended-system-prompts)
                                 selection nil [])]
                            (weaver/update! rt (:id run) {:attributes patch})
                            (weaver/update!
                             rt (:id run)
                             {:attributes
                              {:harness/guidance-capability
                               (assoc capability-document "unknown" true)}})
                            (weaver/show rt (:id run))))
                        [:codex :pi])
                       corrupt (into corrupt-legacy corrupt-native)
                       before corrupt
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
                         (mapv #(capture-failure
                                 (fn [] (harnesses/begin-attempt! rt (:id %))))
                               corrupt))
                       after (mapv #(weaver/show rt (:id %)) corrupt)]
                   {:valid-states
                    (mapv #(let [record
                                 (first (attr (:strand %)
                                              :harness/guidance-attempts))]
                             (or (get record "state")
                                 (get record :state)))
                          valid)
                    :historical-status (attr (:strand historical-start)
                                             :harness/status)
                    :errors (mapv ex-message errors)
                    :unchanged (= before after)
                    :identity-before
                    (mapv #(select-keys (:attributes %)
                                        [:identity/id :identity/reservation-id
                                         :harness/session-id
                                         :harness/provisional-session-id])
                          before)
                    :identity-after
                    (mapv #(select-keys (:attributes %)
                                        [:identity/id :identity/reservation-id
                                         :harness/session-id
                                         :harness/provisional-session-id])
                          after)
                    :attempts (mapv #(attr % :harness/attempt) after)
                    :invocations (mapv #(attr % :harness/invocation) after)
                    :helper-calls @helper-calls
                    :provider-calls @provider-calls}))))]
        (is (= ["not-required" "not-required"] (:valid-states result)))
        (is (= "running" (:historical-status result)))
        (is (every? #(re-find #"corrupt partial guidance metadata" %)
                    (:errors result)))
        (is (true? (:unchanged result)))
        (is (= (:identity-before result) (:identity-after result)))
        (is (= [nil nil nil nil] (:attempts result)))
        (is (= [nil nil nil nil] (:invocations result)))
        (is (zero? (:helper-calls result)))
        (is (zero? (:provider-calls result)))))))

(deftest begin-attempt-reloads-after-waiting-for-publication-lock
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(do
                 (require '[ct.spools.harnesses.catalog :as catalog])
                 (let [run (harnesses/create!
                            rt {:harness :codex :mode :headless
                                :prompt "locked reload" :cwd "/tmp"
                                :guidance-transport "legacy"})
                       started (promise)
                       attempt
                       (locking (catalog/publication-lock rt)
                         (let [future (future
                                        (deliver started true)
                                        (try
                                          (harnesses/begin-attempt! rt (:id run))
                                          nil
                                          (catch Throwable error error)))]
                           @started
                           (weaver/update!
                            rt (:id run)
                            {:attributes {:harness/guidance-version nil}})
                           future))
                       error @attempt
                       after (weaver/show rt (:id run))]
                   {:message (ex-message error)
                    :status (attr after :harness/status)
                    :attempt (attr after :harness/attempt)
                    :invocation (attr after :harness/invocation)}))))]
        (is (re-find #"corrupt partial guidance metadata" (:message result)))
        (is (= "ready" (:status result)))
        (is (nil? (:attempt result)))
        (is (nil? (:invocation result)))))))

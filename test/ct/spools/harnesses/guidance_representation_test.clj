(ns ct.spools.harnesses.guidance-representation-test
  "Closed guidance representation and no-write start regressions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-context :as context]
            [ct.spools.harnesses.guidance-representation-fixture :as fixture]
            [ct.spools.harnesses.internal.guidance-representation :as representation]
            [ct.spools.harnesses.internal.strict-json :as strict-json]))

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
                 :identity/prompt (get template "identity-instruction")
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
   "harness" "codex"
   "mode" "headless"
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

(defn- active-native-run [harness state]
  (let [run (valid-run harness "native-v1")
        attributes (:attributes run)
        fetched? (contains? #{"fetched" "acknowledged"} state)
        fetched-at "2026-09-14T00:00:05Z"
        session-id "fixture-native-session"
        record
        (cond-> {"attempt" 1
                 "invocation" "invocation-1"
                 "transport" "native-v1"
                 "harness" harness
                 "mode" "headless"
                 "state" state
                 "started-at" "2026-09-14T00:00:00Z"
                 "deadline-at" "2026-09-14T00:00:20Z"
                 "bundle-sha256"
                 (:harness/guidance-bundle-sha256 attributes)
                 "capability-sha256"
                 (:harness/guidance-capability-sha256 attributes)}
          fetched?
          (assoc "first-fetch"
                 {"attempt" 1
                  "invocation" "invocation-1"
                  "fetched-at" fetched-at
                  "native-session-id" session-id
                  "authority" "harness-managed-startup/v1"})
          (= "acknowledged" state)
          (assoc "acknowledged-at" "2026-09-14T00:00:10Z"))]
    (cond-> (update run :attributes assoc
                    :harness/attempt 1
                    :harness/invocation "invocation-1"
                    :harness/started-at "2026-09-14T00:00:00Z"
                    :harness/guidance-attempts [record])
      fetched?
      (update :attributes assoc
              :harness/native-attached "true"
              :harness/native-attached-at fetched-at
              :harness/native-attachment-source "managed-startup"
              :harness/native-attachment-attempt 1
              :harness/native-attachment-invocation "invocation-1"
              :harness/session-id session-id))))

(deftest complete-shared-discriminator-accepts-only-valid-representations
  (testing "wholly absent historical metadata remains legacy"
    (is (= {:versioned? false :transport "legacy" :attempts []}
           (guidance/validate-representation!
            {:id "historical" :attributes {}})))
    (is (corrupt?
         {:id "present-nils"
          :attributes (zipmap representation/attribute-keys (repeat nil))})))
  (doseq [harness ["codex" "pi"]]
    (testing (str harness " cannot start the removed native guidance transport")
      (let [legacy (valid-run harness "legacy")
            native (valid-run harness "native-v1")]
        (is (= "not-required"
               (get-in (guidance/begin-attempt-patch
                        nil legacy 1 "legacy-invocation")
                       [:harness/guidance-attempts 0 "state"])))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Native guidance is no longer supported"
             (guidance/begin-attempt-patch nil native 1 "native-invocation")))))
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
      (let [native-history
            (assoc (pending-attempt 1)
                   "state" "failed"
                   "failure" {"stage" "handoff"
                              "code" "fixture"
                              "diagnostic" "retired"})
            valid-history [(fixture/retired-record native-history)
                           (legacy-attempt 2)]
            legacy (update (valid-run harness "legacy") :attributes assoc
                           :harness/attempt 2
                           :harness/invocation "invocation-2"
                           :harness/started-at "2026-09-14T00:00:00Z")]
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

(deftest active-attempt-selection-and-deadline-are-closed
  (doseq [harness ["codex" "pi"]]
    (let [run (active-native-run harness "pending")
          record (get-in run [:attributes :harness/guidance-attempts 0])]
      (is (= "native-v1" (:transport
                          (guidance/validate-representation! run))))
      (doseq [corrupt
              [(assoc-in run [:attributes :identity/prompt] "changed")
               (assoc-in run [:attributes :harness/attempt] 2)
               (assoc-in run [:attributes :harness/invocation] "other")
               (assoc-in run [:attributes :harness/started-at]
                         "2026-09-14T00:00:01Z")
               (assoc-in run
                         [:attributes :harness/guidance-attempts 0
                          "transport"]
                         "legacy")
               (assoc-in run
                         [:attributes :harness/guidance-attempts 0
                          "deadline-at"]
                         "2026-09-14T00:00:21Z")
               (assoc-in run
                         [:attributes :harness/guidance-attempts 0
                          "bundle-sha256"]
                         sha-a)
               (assoc-in run
                         [:attributes :harness/guidance-attempts 0
                          "capability-sha256"]
                         sha-b)
               (-> run
                   (assoc-in [:attributes :harness/attempt] 2)
                   (assoc-in [:attributes :harness/invocation] "invocation-1")
                   (assoc-in [:attributes :harness/guidance-attempts]
                             [record (assoc record "attempt" 2)]))]]
        (is (corrupt? corrupt))))))

(deftest only-fetched-interactive-pi-allows-delayed-first-turn-acknowledgement
  (let [expired-at (java.time.Instant/parse "2026-09-14T00:00:21Z")
        with-mode #(-> %1
                       (assoc-in [:attributes :harness/mode] %2)
                       (assoc-in [:attributes :harness/guidance-attempts 0
                                  "mode"] %2))
        fetched-pi (with-mode (active-native-run "pi" "fetched")
                     "interactive")
        expired? #(guidance/deadline-expired?
                   % (guidance/current-attempt %) expired-at)]
    (is (false? (expired? fetched-pi)))
    (is (true? (expired?
                (with-mode (active-native-run "pi" "pending")
                  "interactive"))))
    (is (true? (expired? (with-mode fetched-pi "headless"))))
    (let [late-ack
          (fn [run]
            (-> run
                (assoc-in [:attributes :harness/guidance-attempts 0 "state"]
                          "acknowledged")
                (assoc-in [:attributes :harness/guidance-attempts 0
                           "acknowledged-at"]
                          "2026-09-14T00:00:21Z")))]
      (is (not (corrupt? (late-ack fetched-pi))))
      (is (corrupt? (late-ack (with-mode fetched-pi "headless"))))
      (is (corrupt? (late-ack
                     (with-mode (active-native-run "codex" "fetched")
                       "interactive")))))))

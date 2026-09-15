(ns ct.spools.harnesses.guidance-representation-fixture
  "Valid in-memory durable guidance rows for pure protocol tests."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.strict-json :as strict-json]))

(def ^:private sha-a (str/join (repeat 64 "a")))
(def ^:private sha-b (str/join (repeat 64 "b")))

(defn capability-document
  "Return a closed test capability for `harness`."
  [harness]
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" harness
   "adapter-contract" "native-v1"
   "adapter-sha256" sha-a
   "executable-sha256" sha-b
   "host-version" (if (= "codex" harness) "0.154.0" "0.84.4")
   "launch-profile-sha256" sha-a
   "max-context-bytes" (if (= "codex" harness) 3072 65536)
   "hook-fact"
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
      "system-prompt-options-contract" "owned-v1"})})

(defn run
  "Return a valid versioned provider run under the /tmp workspace."
  [harness transport]
  (let [rt {:metadata {:config-dir "/tmp"}}
        run-id "run"
        capability (capability-document harness)
        selection
        (cond-> {:transport transport}
          (= "native-v1" transport)
          (assoc :capability capability
                 :capability-sha256
                 (strict-json/canonical-sha256 capability)))
        guidance-attributes
        (cond-> (guidance/publication-patch
                 rt run-id "fixture-identity" "Identity first." ["same" "same"]
                 selection nil [])
          (= "legacy" transport)
          (dissoc :harness/guidance-capability
                  :harness/guidance-capability-sha256))]
    (guidance/validation-run
     rt
     {:id run-id
      :title (str harness " run")
      :state "active"
      :attributes
      (merge guidance-attributes
             {:harness/harness harness
              :harness/mode "headless"
              :harness/session-id "native-session"
              :harness/prompt "Main task"
              :identity/id "fixture-identity"
              :identity/prompt "Identity first."
              :harness/appended-system-prompts ["same" "same"]
              :harness/model "model"
              :harness/effort "high"
              :harness/extra-argv ["--unrelated" "value"]})})))

(defn with-pending-attempt
  "Add one valid pending native attempt to `run`."
  [run]
  (let [attributes (:attributes run)
        started-at "2026-09-13T23:59:40Z"
        invocation "invocation"]
    (update
     run :attributes
     assoc
     :harness/attempt 1
     :harness/invocation invocation
     :harness/started-at started-at
     :harness/guidance-attempts
     [{"attempt" 1
       "invocation" invocation
       "transport" "native-v1"
       "harness" (:harness/harness attributes)
       "mode" (:harness/mode attributes)
       "state" "pending"
       "started-at" started-at
       "deadline-at" "2026-09-14T00:00:00Z"
       "bundle-sha256" (:harness/guidance-bundle-sha256 attributes)
       "capability-sha256"
       (:harness/guidance-capability-sha256 attributes)}])))

(defn retired-record
  "Add closed retirement evidence to native `record`."
  [record]
  (let [first-fetch (get record "first-fetch")
        evidence
        (cond-> {"attempt" (get record "attempt")
                 "invocation" (get record "invocation")
                 "authority" "harness-retirement/v1"
                 "settlement" (if (contains? record "no-launch")
                                "launch-not-started"
                                "process-exit")}
          (not (contains? record "no-launch"))
          (assoc "exit-code" 0)
          first-fetch
          (assoc "attachment-session-id"
                 (get first-fetch "native-session-id")
                 "attachment-at" (get first-fetch "fetched-at")
                 "attachment-source" "managed-startup"))]
    (assoc record "retired-evidence" evidence)))

(defn with-retired-attempt
  "Add closed retirement evidence to attempt `index` in `run`."
  ([run] (with-retired-attempt run 0))
  ([run index]
   (update-in run [:attributes :harness/guidance-attempts index]
              retired-record)))

(defn with-fetched-attempt
  "Add timely first-fetch and matching attachment evidence to `run`."
  [run]
  (let [record (get-in run [:attributes :harness/guidance-attempts 0])
        session-id "fixture-native-session"
        fetched-at "2026-09-13T23:59:50Z"]
    (-> run
        (assoc-in [:attributes :harness/guidance-attempts 0 "state"] "fetched")
        (assoc-in
         [:attributes :harness/guidance-attempts 0 "first-fetch"]
         {"attempt" (get record "attempt")
          "invocation" (get record "invocation")
          "fetched-at" fetched-at
          "native-session-id" session-id
          "authority" "harness-managed-startup/v1"})
        (update :attributes assoc
                :harness/native-attached "true"
                :harness/native-attached-at fetched-at
                :harness/native-attachment-source "managed-startup"
                :harness/native-attachment-attempt (get record "attempt")
                :harness/native-attachment-invocation
                (get record "invocation")
                :harness/session-id session-id))))

(ns ct.spools.harnesses.guidance-provider-transport-test
  "Pure provider routing checks over complete durable guidance rows."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.guidance-representation-fixture :as fixture]
            [ct.spools.harnesses.internal.guidance :as guidance]
            [ct.spools.harnesses.internal.guidance-capability :as capability]
            [ct.spools.harnesses.providers.claude :as claude]
            [ct.spools.harnesses.providers.codex :as codex]
            [ct.spools.harnesses.providers.cursor :as cursor]
            [ct.spools.harnesses.providers.pi :as pi])
  (:import [java.time Instant]))

(def ^:private runtime {:metadata {:config-dir "/tmp"}})

(deftest guidance-deadlines-distinguish-pi-idle-after-fetch
  (let [run (-> (fixture/run "pi" "native-v1")
                fixture/with-pending-attempt
                (assoc-in [:attributes :harness/attempt] 1)
                (assoc-in [:attributes :harness/invocation] "invocation"))
        record (first (guidance/attempt-records run))
        pi-run (-> (fixture/run "pi" "native-v1")
                   fixture/with-pending-attempt
                   fixture/with-fetched-attempt
                   (assoc-in [:attributes :harness/mode] "interactive")
                   (assoc-in [:attributes :harness/guidance-attempts 0 "mode"]
                             "interactive"))
        pi-record (first (guidance/attempt-records pi-run))
        after (Instant/parse "2026-09-14T00:00:01Z")]
    (is (true? (guidance/deadline-expired? run record after)))
    (is (false? (guidance/deadline-expired? pi-run pi-record after)))
    (is (true? (guidance/deadline-expired?
                (-> (fixture/run "pi" "native-v1")
                    fixture/with-pending-attempt)
                (assoc record "state" "fetched") after)))))

(deftest production-admission-is-disabled-and-hostile-argv-fails-first
  (is (empty? (capability/production-allowlist)))
  (doseq [harness ["codex" "pi"]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"transport selection is unsupported"
         (guidance/select!
          {:metadata {:config-dir "/tmp"}}
          {:harness harness :requested "native-v1" :mode :headless
           :cwd "/tmp" :env {} :effective {:harness/extra-argv []}})))))

(deftest provider-argv-removes-only-harnesses-guidance-in-native-mode
  (let [legacy-codex (codex/prepare runtime (codex/harness runtime)
                                    (fixture/run "codex" "legacy"))
        native-codex (codex/prepare runtime (codex/harness runtime)
                                    (fixture/run "codex" "native-v1"))
        legacy-pi (pi/prepare runtime (pi/harness runtime)
                              (fixture/run "pi" "legacy"))
        native-pi (pi/prepare runtime (pi/harness runtime)
                              (fixture/run "pi" "native-v1"))]
    (is (some #(str/starts-with? % "developer_instructions=")
              (:argv legacy-codex)))
    (is (some #(str/starts-with? % "developer_instructions=")
              (:argv native-codex)))
    (is (= 2 (count (filter #{"--append-system-prompt"}
                            (:argv legacy-pi)))))
    (is (= 2 (count (filter #{"--append-system-prompt"}
                            (:argv native-pi)))))
    (doseq [launch [native-codex native-pi]]
      (is (= "Main task\n" (:stdin launch)))
      (is (some #{"model"} (:argv launch)))
      (is (some #{"--unrelated"} (:argv launch)))))
  (testing "maintenance providers retain their exact launch preparation"
    (let [run (fixture/run "maintenance" "legacy")]
      (is (= (claude/prepare runtime (claude/harness runtime) run)
             (claude/prepare runtime (claude/harness runtime) run)))
      (is (= (cursor/prepare runtime (cursor/harness runtime) run)
             (cursor/prepare runtime (cursor/harness runtime) run))))))

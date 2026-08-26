(ns ct.spools.harnesses.providers.cursor-test
  "Provider-boundary tests for Cursor launch preparation and JSON normalization."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.providers.cursor :as cursor]))

(def ^:private plugin-dir
  (.getCanonicalPath (io/file "plugins" "cursor" "harness")))
(def ^:private runtime {})
(def ^:private definition (cursor/harness runtime))

(defn- run
  ([mode]
   (run mode {}))
  ([mode attributes]
   {:id "run"
    :title "Cursor run"
    :state "active"
    :attributes
    (merge {:harness/mode mode
            :harness/session-id "provisional"
            :harness/prompt "Do the work"
            :identity/prompt "You are agent tidy-brave-swan."
            :harness/model "composer-2.5[fast=false]"
            :harness/effort "adaptive"
            :harness/extra-argv ["--yolo" "--trust"]}
           attributes)}))

(deftest prepare-builds-new-and-resumed-launch-specifications
  (testing "new headless runs expose identity for Cursor system-context injection"
    (is (= {:argv ["agent" "--print" "--output-format" "json"
                   "--model" "composer-2.5[fast=false]" "--thinking" "adaptive"
                   "--plugin-dir" plugin-dir "--yolo" "--trust"]
            :env {"MILLSTRAND_HARNESS_CURSOR_SYS_PROMPT"
                  "You are agent tidy-brave-swan."}
            :stdin "Do the work\n"}
           (cursor/prepare runtime definition (run "headless")))))
  (testing "resumed headless runs select the recorded Cursor chat"
    (is (= {:argv ["agent" "--print" "--output-format" "json"
                   "--resume" "provisional"
                   "--model" "composer-2.5[fast=false]" "--thinking" "adaptive"
                   "--plugin-dir" plugin-dir "--yolo" "--trust"]
            :stdin "Do the work\n"}
           (cursor/prepare runtime definition
                           (run "headless" {:harness/resumes "prior"})))))
  (testing "interactive runs retain a host-TTY prompt and expose identity"
    (is (= {:argv ["agent" "--model" "composer-2.5[fast=false]" "--thinking" "adaptive"
                   "--plugin-dir" plugin-dir "--yolo" "--trust" "Do the work"]
            :env {"MILLSTRAND_HARNESS_CURSOR_SYS_PROMPT"
                  "You are agent tidy-brave-swan."}
            :stdin nil}
           (cursor/prepare runtime definition (run "interactive"))))))

(deftest finish-normalizes-result-and-provider-session
  (let [stdout (str "{\"type\":\"result\",\"subtype\":\"success\","
                    "\"is_error\":false,\"result\":\"final\","
                    "\"session_id\":\"session-1\"}")]
    (is (= {:status :done
            :exit-code 0
            :result "final"
            :session-id "session-1"}
           (cursor/finish runtime definition (run "headless")
                          {:exit-code 0 :stdout stdout :stderr ""})))))

(deftest finish-fails-loudly-on-incomplete-success-output
  (testing "a successful process without a provider session cannot be resumed safely"
    (let [outcome (cursor/finish runtime definition (run "headless")
                                 {:exit-code 0
                                  :stdout "{\"result\":\"final\"}"
                                  :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"no session id" (:error outcome)))))
  (testing "an error result becomes a normalized failure"
    (let [outcome (cursor/finish runtime definition (run "headless")
                                 {:exit-code 0
                                  :stdout "{\"is_error\":true,\"result\":\"nope\",\"session_id\":\"session-1\"}"
                                  :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (= "nope" (:error outcome)))))
  (testing "malformed JSON becomes a normalized failure"
    (let [outcome (cursor/finish runtime definition (run "headless")
                                 {:exit-code 0 :stdout "{nope}" :stderr ""})]
      (is (= :failed (:status outcome)))
      (is (re-find #"JSON parse failed" (:error outcome))))))

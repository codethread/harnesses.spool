(ns ct.spools.harnesses.managed-startup-deadline-test
  "First-fetch preparation and strict deadline boundary regressions."
  (:require [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(deftest first-fetch-clock-starts-after-reservation-preparation
  (guidance-test/with-guidance-world
    (fn [ctx]
      (let [result
            (test-alpha/repl!
             ctx
             (list
              'do guidance-test/lifecycle-setup
              '(do
                 (require
                  '[ct.spools.harnesses.internal.lifecycle :as life]
                  '[ct.spools.harnesses.internal.managed-identity
                    :as managed-identity])
                 (binding
                  [capability/*test-capability-profiles* [profile]
                   capability/*test-preflight-runner* accepted-runner]
                   (let [new-run!
                         (fn [suffix]
                           (let [run
                                 (harnesses/create!
                                  rt {:harness :native-codex
                                      :mode :headless
                                      :cwd "/tmp"
                                      :prompt (str "deadline " suffix)
                                      :guidance-transport "native-v1"})
                                 started
                                 (harnesses/begin-attempt! rt (:id run))]
                             {:run run
                              :started started
                              :request
                              {:harness "codex"
                               :native-session-id
                               (str "session-" suffix)
                               :cwd "/tmp"
                               :scope "root"
                               :bootstrap
                               (harnesses/managed-bootstrap rt (:id run))
                               :guidance
                               (guidance/bootstrap (:strand started))}}))
                         original-binding
                         managed-identity/reservation-binding
                         original-persist
                         managed-identity/persist-attachment!
                         rejected
                         (mapv
                          (fn [[label adjust]]
                            (let [{:keys [run started request]}
                                  (new-run! label)
                                  deadline
                                  (java.time.Instant/parse
                                   (get (guidance/current-attempt
                                         (:strand started))
                                        "deadline-at"))
                                  transition-at (str (adjust deadline))
                                  events (atom [])
                                  persist-calls (atom 0)
                                  before (vec (sort-by :id (weaver/list rt)))
                                  error
                                  (with-redefs
                                   [managed-identity/reservation-binding
                                    (fn [& args]
                                      (let [result
                                            (apply original-binding args)]
                                        (swap! events conj :prepared)
                                        result))
                                    life/now
                                    (fn []
                                      (swap! events conj :timestamp)
                                      transition-at)
                                    managed-identity/persist-attachment!
                                    (fn [& args]
                                      (swap! persist-calls inc)
                                      (apply original-persist args))]
                                    (try
                                      (harnesses/managed-startup! rt request)
                                      nil
                                      (catch clojure.lang.ExceptionInfo failure
                                        (ex-message failure))))
                                  after (vec (sort-by :id (weaver/list rt)))
                                  stored
                                  (guidance/validation-run
                                   rt (weaver/show rt (:id run)))]
                              {:label label
                               :error error
                               :events @events
                               :persist-calls @persist-calls
                               :no-write (= before after)
                               :attached
                               (attr stored :harness/native-attached)
                               :state
                               (get (guidance/current-attempt stored)
                                    "state")}))
                          [["equal" identity]
                           ["late" #(.plusMillis % 1)]])
                         {:keys [run started request]} (new-run! "timely")
                         deadline
                         (java.time.Instant/parse
                          (get (guidance/current-attempt (:strand started))
                               "deadline-at"))
                         timely-at (str (.minusMillis deadline 1))
                         events (atom [])
                         bundle
                         (with-redefs
                          [managed-identity/reservation-binding
                           (fn [& args]
                             (let [result (apply original-binding args)]
                               (swap! events conj :prepared)
                               result))
                           life/now
                           (fn []
                             (swap! events conj :timestamp)
                             timely-at)]
                           (harnesses/managed-startup! rt request))
                         timely
                         (guidance/validation-run
                          rt (weaver/show rt (:id run)))
                         before-replay (vec (sort-by :id (weaver/list rt)))
                         replay
                         (with-redefs
                          [life/now
                           (fn []
                             (throw
                              (ex-info "replay must not capture time" {})))]
                           (harnesses/managed-startup! rt request))
                         after-replay (vec (sort-by :id (weaver/list rt)))
                         record (guidance/current-attempt timely)]
                     {:rejected rejected
                      :timely-events @events
                      :timely-at timely-at
                      :attached-at
                      (attr timely :harness/native-attached-at)
                      :first-fetch-at
                      (get-in record ["first-fetch" "fetched-at"])
                      :bundle-schema (:schema bundle)
                      :replay-equal (= bundle replay)
                      :replay-no-write (= before-replay after-replay)})))))]
        (is (= #{"equal" "late"} (set (map :label (:rejected result)))))
        (is (every? #(re-find #"crossed its handoff deadline" (:error %))
                    (:rejected result)))
        (is (every? #(= [:prepared :timestamp] (:events %))
                    (:rejected result)))
        (is (every? zero? (map :persist-calls (:rejected result))))
        (is (every? :no-write (:rejected result)))
        (is (every? #(= "false" (:attached %)) (:rejected result)))
        (is (every? #(= "pending" (:state %)) (:rejected result)))
        (is (= [:prepared :timestamp] (:timely-events result)))
        (is (= (:timely-at result)
               (:attached-at result)
               (:first-fetch-at result)))
        (is (= "millstrand.agent-guidance-bundle/v1"
               (:bundle-schema result)))
        (is (true? (:replay-equal result)))
        (is (true? (:replay-no-write result)))))))

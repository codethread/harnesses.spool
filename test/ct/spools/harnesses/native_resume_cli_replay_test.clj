(ns ct.spools.harnesses.native-resume-cli-replay-test
  "Public CLI regression coverage for exact native-resume request replay."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.test.alpha :as test-alpha]))

(deftest exact-cli-resume-request-replays-its-original-child
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    (test-alpha/with-weaver-world
      [ctx {:storage :sqlite-memory
            :deps-edn
            (pr-str
             {:deps
              {'ct.spools/harnesses
               {:local/root (.getCanonicalPath harnesses-root)}
               'millhouse.spools/identity
               {:local/root (.getCanonicalPath identity-root)}}})
            :init-clj
            "(require '[millstrand.api.current.alpha :as current]
                       '[millstrand.api.runtime.alpha :as runtime])
             (def rt (current/runtime))
             (runtime/module! rt :identity
               {:ns 'millhouse.spools.identity :required? true})
             (runtime/module! rt :harnesses
               {:ns 'ct.spools.harnesses.spool
                :after [:identity]
                :required? true})"}]
      (is
       (true?
        (test-alpha/repl!
         ctx
         '(do
            (require '[ct.spools.harnesses :as harnesses]
                     '[millstrand.api.current.alpha :as current]
                     '[millstrand.api.spool.alpha :as spool]
                     '[millstrand.api.weaver.alpha :as weaver])
            (defn fake-prepare [_rt _definition _run]
              {:argv ["/bin/true"] :env {} :stdin nil})
            (let [rt (current/runtime)
                  _ (harnesses/register-harness!
                     rt :fake
                     {:modes #{:interactive}
                      :prepare (symbol (str (ns-name *ns*)) "fake-prepare")
                      :finish 'ct.spools.harnesses/finish!})
                  attr spool/attr-get
                  selector-cases
                  [[:run-id #(identity (:id %))]
                   [:session-id #(attr % :harness/session-id)]
                   [:identity #(attr % :identity/id)]
                   [:logical-id #(attr % :harness/logical-id)]]
                  results
                  (mapv
                   (fn [[selector-key selector-value]]
                     (let [target (weaver/add! rt {:title "replay target"})
                           root (harnesses/create!
                                 rt {:harness :fake
                                     :mode :interactive
                                     :target (:id target)})
                           root (harnesses/finish!
                                 rt (:id root)
                                 {:status :done :exit-code 0
                                  :session-usable true})
                           selector (selector-value root)
                           flag (str "--" (name selector-key))
                           request-id (str "replay-" (name selector-key))
                           argv ["resume" flag selector "--interactive"
                                 "--request-id" request-id
                                 "--by-identity" "cli-actor"]
                           first (weaver/op! rt 'agent argv)
                           before-count
                           (count (weaver/list
                                   rt [:= [:attr "harness/run"] "true"] {}))
                           active-replay (weaver/op! rt 'agent argv)
                           child (harnesses/finish!
                                  rt (:id first)
                                  {:status :done :exit-code 0
                                   :session-usable true})
                           grandchild (harnesses/resume! rt (:id child) {})
                           advanced-replay (weaver/op! rt 'agent argv)
                           changed
                           (try
                             (weaver/op! rt 'agent
                                         (conj argv "--title" "changed"))
                             :accepted
                             (catch Exception error (ex-message error)))
                           changed-selector
                           (try
                             (weaver/op!
                              rt 'agent
                              ["resume"
                               (if (= selector-key :run-id)
                                 "--logical-id"
                                 "--run-id")
                               (if (= selector-key :run-id)
                                 (attr root :harness/logical-id)
                                 (:id root))
                               "--interactive" "--request-id" request-id
                               "--by-identity" "cli-actor"])
                             :accepted
                             (catch Exception error (ex-message error)))
                           stale
                           (try
                             (weaver/op!
                              rt 'agent
                              ["resume" "--run-id" (:id root)
                               "--interactive" "--request-id"
                               (str request-id "-fresh")])
                             :accepted
                             (catch Exception error (ex-message error)))
                           after-count
                           (count (weaver/list
                                   rt [:= [:attr "harness/run"] "true"] {}))
                           stored (weaver/show rt (:id first))]
                       {:same (= (:id first) (:id active-replay)
                                 (:id advanced-replay))
                        :count (= (+ before-count 1) after-count)
                        :changed changed
                        :changed-selector changed-selector
                        :stale stale
                        :selector (= {selector-key selector}
                                     (attr stored
                                           :harness/resume-selector-intent))
                        :custody (= [(attr root :identity/id)
                                     (attr root :harness/session-id)
                                     (:id target)]
                                    [(attr stored :identity/id)
                                     (attr stored :harness/session-id)
                                     (attr stored :harness/target)])
                        :grandchild (:id grandchild)}))
                   selector-cases)]
              (every?
               (fn [{:keys [same count changed changed-selector stale selector
                            custody]}]
                 (and same count selector custody
                      (re-find #"already held" changed)
                      (re-find #"already held" changed-selector)
                      (re-find #"already has an accepted continuation" stale)))
               results)))))))))

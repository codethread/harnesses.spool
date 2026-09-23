(ns ct.spools.harnesses.publication-test
  "Interrupted publication, exact replay, and restored-world acceptance."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.assignment-test :as fixture]
            [ct.spools.harnesses.execution :as execution]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]
           [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]))

(deftest interrupted-publication-is-terminal-and-replay-does-not-enrich
  (fixture/with-assignment-world
    (fn [ctx]
      (let [results
            (fixture/eval-world
             ctx
             '(do
                (require '[ct.spools.harnesses.internal.managed-startup :as managed]
                         '[ct.spools.harnesses.agent-cli]
                         '[clojure.spec.alpha :as spec]
                         '[ct.spools.harnesses.assignment.cli :as assignment-cli])
                (mapv
                 (fn [seam]
                   (let [target (add-target! (name seam))
                         request {:harness :codex :request-id (name seam)}
                         failure (ex-info "publication seam failed" {})
                         error
                         (with-redefs-fn
                           {(case seam
                              :created #'managed/commit-identity!
                              :published #'assignment-internal/enrich-guidance!)
                            (fn [& _] (throw failure))}
                           #(try (assign! (:id target) request)
                                 nil
                                 (catch Exception e (ex-message e))))
                         retained (first (weaver/list
                                          rt [:= [:attr "harness/request-id"]
                                              (name seam)] {}))
                         before (vec (sort-by :id (weaver/list rt)))
                         replay (with-redefs
                                 [assignment-internal/enrich-guidance!
                                  (fn [& _] (throw (ex-info "replayed enrichment" {})))]
                                  (assign! (:id target) request))
                         shown (harnesses/run rt (:id retained))
                         begin-error (try (harnesses/begin-attempt! rt (:id retained))
                                          nil
                                          (catch Exception e (ex-message e)))
                         opened (#'execution/activate-state! rt)
                         scheduled (try (execution/schedule! rt)
                                        (finally ((:close-fn
                                                   (#'execution/deactivate-state! rt)))))]
                     {:seam seam :error error
                      :receipt (assignment-cli/assign-summary replay)
                      :receipt-valid (spec/valid? :ct.spools.harnesses.agent-cli/run-summary
                                                  (assignment-cli/assign-summary replay))
                      :same (= retained replay shown)
                      :no-writes (= before (vec (sort-by :id (weaver/list rt))))
                      :identities (count (weaver/list rt
                                                      [:= [:attr "identity/session"] "true"] {}))
                      :reservation (attr replay :identity/reservation-id)
                      :performed (count (graph/incoming-edges rt [(:id replay)] "performed"))
                      :serves (outgoing (:id replay) "serves")
                      :target (:id target)
                      :settlement (attr replay :harness/settlement)
                      :scheduled scheduled :begin-error begin-error}))
                 [:created :published])))]
        (doseq [result results]
          (is (= "publication seam failed" (:error result)))
          (is (= "interrupted" (get-in result [:receipt :publication-outcome])))
          (is (= (name (:seam result)) (get-in result [:receipt :publication-phase])))
          (is (= "stopped" (get-in result [:receipt :status])))
          (is (= "never-launched" (:settlement result)))
          (is (true? (get-in result [:receipt :settled])))
          (is (:receipt-valid result))
          (is (:same result))
          (is (:no-writes result))
          (is (= [(:target result)] (:serves result)))
          (is (empty? (:scheduled result)))
          (is (string? (:begin-error result))))
        (is (= [0 0] (mapv :identities results)))
        (is (= [0 0] (mapv :performed results)))
        (is (nil? (:reservation (second results))))))))

(deftest lost-success-response-retains-committed-assignment
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(do
                (require '[ct.spools.harnesses.internal.publication :as publication])
                (let [target (add-target! "Lost successful response")
                      original publication/complete!
                      request {:harness :codex :request-id "lost-success"}
                      error (with-redefs
                             [publication/complete!
                              (fn [& args]
                                (apply original args)
                                (throw (ex-info "response lost" {})))]
                              (try (assign! (:id target) request)
                                   nil
                                   (catch Exception e (ex-message e))))
                      before (vec (sort-by :id (weaver/list rt)))
                      replay (assign! (:id target) request)]
                  {:error error :outcome (attr replay :harness/publication-outcome)
                   :ready (assignment/launch-ready? rt replay)
                   :no-writes (= before (vec (sort-by :id (weaver/list rt))))
                   :runs (count (weaver/list rt [:= [:attr "harness/run"] "true"] {}))
                   :identities (count (weaver/list rt [:= [:attr "identity/session"] "true"] {}))})))]
        (is (= {:error "response lost" :outcome "committed" :ready true
                :no-writes true :runs 1 :identities 0}
               result))))))

(deftest interrupted-child-is-not-an-accepted-lineage-head
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(let [target (add-target! "Retained predecessor")
                    parent (assign! (:id target) {:request-id "parent"})
                    _ (harnesses/finish! rt (:id parent)
                                         {:status :done :exit-code 0 :result "accepted"})
                    _ (with-redefs [assignment-internal/enrich-guidance!
                                    (fn [& _] (throw (ex-info "enrichment failed" {})))]
                        (try (assign! (:id target) {:after (:id parent)
                                                    :request-id "child"})
                             (catch Exception _ nil)))
                    child (assign! (:id target) {:after (:id parent) :request-id "child"})
                    head (harnesses/resolve-resume-run
                          rt {:logical-id (attr parent :harness/logical-id)})
                    continued (attr (harnesses/run rt (:id parent)) :harness/continued)
                    rejected (try (assign! (:id target) {:after (:id child) :request-id "bad"})
                                  nil (catch Exception e (ex-message e)))
                    authorized (assign! (:id target) {:after (:id parent) :request-id "authorized"})]
                {:parent (:id parent) :head (:id head) :continued continued
                 :child-history (outgoing (:id child) "continues")
                 :authorized-history (outgoing (:id authorized) "continues")
                 :rejected rejected}))]
        (is (= (:parent result) (:head result)))
        (is (nil? (:continued result)))
        (is (= [(:parent result)] (:child-history result) (:authorized-history result)))
        (is (re-find #"not accepted" (:rejected result)))))))

(deftest restored-world-disposes-both-retained-seams-without-launch
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       (java.nio.file.Path/of "/tmp" (make-array String 0)) "pub-" (make-array java.nio.file.attribute.FileAttribute 0)))
        opts {:root (.getPath root) :storage :sqlite-file}]
    (try
      (let [ids
            (fixture/with-assignment-world
              opts
              (fn [ctx]
                (fixture/eval-world
                 ctx
                 '(do
                    (require '[ct.spools.harnesses.internal.publication :as publication]
                             '[ct.spools.harnesses.internal.managed-startup :as managed])
                    (mapv
                     (fn [seam]
                       (let [target (add-target! (name seam))]
                         ;; Model process loss: retain the partial writes without
                         ;; allowing the in-process exception handler to settle.
                         (with-redefs-fn
                           {#'publication/fail! (fn [_ _ error] (throw error))
                            (case seam
                              :created #'managed/commit-identity!
                              :published #'assignment-internal/enrich-guidance!)
                            (fn [& _] (throw (ex-info "process lost" {})))}
                           #(try (assign! (:id target) {:harness :codex :request-id (name seam)})
                                 (catch Exception _ nil)))
                         (let [run (first (weaver/list
                                           rt [:= [:attr "harness/request-id"] (name seam)] {}))]
                           {:id (:id run) :target (:id target) :key (name seam)
                            :reservation (attr run :identity/reservation-id)})))
                     [:created :published])))))
            result
            (fixture/with-assignment-world
              opts
              (fn [ctx]
                (fixture/eval-world
                 ctx
                 `(let [ids# ~ids
                        opened# (execution/open-execution! {:runtime ~'rt})]
                    (try
                      {:claimed (:claimed opened#)
                       :rows (mapv
                              (fn [{id# :id target# :target key# :key reservation# :reservation}]
                                (let [run# (~'assign! target# {:harness :codex :request-id key#})]
                                  {:same (= id# (:id run#))
                                   :reservation (= reservation# (~'attr run# :identity/reservation-id))
                                   :outcome (~'attr run# :harness/publication-outcome)
                                   :settlement (~'attr run# :harness/settlement)})) ids#)}
                      (finally (execution/close-execution! {:runtime ~'rt})))))))]
        (is (empty? (:claimed result)))
        (doseq [row (:rows result)]
          (is (:same row))
          (is (:reservation row))
          (is (= "interrupted" (:outcome row)))
          (is (= "never-launched" (:settlement row)))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete ^java.io.File file))))))

(defn- invoke-agent
  [ctx argv timeout-ms]
  (let [metadata (:metadata ctx)]
    (with-open [channel (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                          (.connect (UnixDomainSocketAddress/of (:socket-path metadata))))
                reader (BufferedReader. (InputStreamReader. (Channels/newInputStream channel)))
                writer (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream channel)))]
      (.write writer
              (json/write-str
               {:protocol_version 3 :request_id "publication-deadline"
                :weaver_id (:nonce metadata) :operation "invoke" :options {}
                :arguments {:name "agent" :argv argv :payloads {}
                            :is_tty false :tty_col nil :timeout timeout-ms}}))
      (.newLine writer)
      (.flush writer)
      (json/read-str (.readLine reader) :key-fn keyword))))

(deftest public-operation-deadline-interrupts-before-assignment-enrichment
  (fixture/with-assignment-world
    (fn [ctx]
      (let [target
            (fixture/eval-world
             ctx
             '(do
                (require '[ct.spools.harnesses.agent-cli]
                         '[ct.spools.harnesses.internal.cli :as cli]
                         '[ct.spools.harnesses.internal.publication :as publication])
                (weaver/register-op! rt 'agent {:arg-spec cli/agent-arg-spec}
                                     'ct.spools.harnesses.agent-cli/agent)
                (#'execution/activate-state! rt)
                (def installed (promise))
                (def entered (promise))
                (def disposed (promise))
                (def restore (promise))
                (def original-fail publication/fail!)
                (def injection
                  (future
                    (with-redefs
                     [assignment-internal/enrich-guidance!
                      (fn [& _]
                        (deliver entered (execution/schedule! rt))
                        (.await (java.util.concurrent.CountDownLatch. 1)))
                      publication/fail!
                      (fn [& args]
                        (try (apply original-fail args)
                             (finally (deliver disposed true))))]
                      (deliver installed true)
                      @restore)))
                @installed
                (:id (add-target! "Socket cancellation"))))
            argv ["assign" "codex" "--task" target "--cwd" "/tmp/assignment-work"
                  "--request-id" "deadline"]]
        (try
          (let [response (invoke-agent ctx argv 2000)
                state (test-alpha/repl!
                       ctx
                       '(do
                          @disposed
                          {:entered (realized? entered)
                           :scheduled @entered
                           :rows (vec (sort-by :id (weaver/list rt)))}))
                replay (invoke-agent ctx argv 5000)
                shown (invoke-agent ctx ["show" "--request" "deadline"] 5000)
                after (test-alpha/repl! ctx '(vec (sort-by :id (weaver/list rt))))]
            (is (= "operation/deadline-exceeded" (get-in response [:error :code])))
            (is (true? (:ok replay)) (pr-str replay))
            (is (:entered state))
            (is (empty? (:scheduled state)))
            (is (= "interrupted" (get-in replay [:result :publication-outcome])))
            (is (= "published" (get-in replay [:result :publication-phase])))
            (is (= "stopped" (get-in replay [:result :status])))
            (is (true? (get-in replay [:result :settled])))
            (is (= (get-in replay [:result :id]) (get-in shown [:result :id])))
            (is (= "interrupted" (get-in shown [:result :publication-outcome])))
            (is (= (:rows state) after)))
          (finally
            (test-alpha/repl! ctx '(do (deliver restore true) @injection
                                       ((:close-fn (#'execution/deactivate-state! rt)))))))))))

(deftest readback-preserves-prior-settlement-and-possible-custody
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(mapv
               (fn [state]
                 (let [target (add-target! (name state))
                       run (assign! (:id target) {})
                       id (:id run)
                       _ (case state
                           :never-launched (harnesses/stop! rt id {:reason "operator stopped"})
                           :running (harnesses/begin-attempt! rt id))
                       before (weaver/show rt id)
                       _ (weaver/update! rt id {:attributes {:harness/context nil}})
                       _ (weaver/update!
                          rt id {:attributes {:harness/publication-outcome nil
                                              :harness/context
                                              {"assignment/policy" "stop-on-complete"}}})
                       shown (harnesses/run rt id)
                       competing (when (= :running state)
                                   (try (assign! (:id target) {}) nil
                                        (catch Exception e (ex-message e))))]
                   {:state state
                    :status (attr shown :harness/status)
                    :settled (attr shown :harness/settled)
                    :settlement (attr shown :harness/settlement)
                    :outcome (attr shown :harness/publication-outcome)
                    :invocation-retained (= (attr before :harness/invocation)
                                            (attr shown :harness/invocation))
                    :reservation-retained (= (attr before :identity/id)
                                             (attr shown :identity/id))
                    :competing competing}))
               [:never-launched :running]))]
        (is (= ["stopped" "running"] (mapv :status result)))
        (is (= ["true" "false"] (mapv :settled result)))
        (is (= ["never-launched" nil] (mapv :settlement result)))
        (is (every? #(= "interrupted" (:outcome %)) result))
        (is (every? :invocation-retained result))
        (is (every? :reservation-retained result))
        (is (re-find #"active managed run" (:competing (second result))))))))

(deftest cancellation-during-final-read-cannot-cross-the-commit-fence
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(do
                (require '[ct.spools.harnesses.internal.publication :as publication])
                (let [target (add-target! "Final-read cancellation")
                      complete publication/complete!
                      show weaver/show
                      interrupted
                      (try
                        (with-redefs
                         [publication/complete!
                          (fn [& args]
                            (with-redefs
                             [weaver/show
                              (fn [& show-args]
                                (let [run (apply show show-args)]
                                  (.interrupt (Thread/currentThread))
                                  run))]
                              (apply complete args)))]
                          (assign! (:id target) {:request-id "final-read"}))
                        false
                        (catch InterruptedException _
                          (.isInterrupted (Thread/currentThread)))
                        (finally (Thread/interrupted)))
                      replay (assign! (:id target) {:request-id "final-read"})]
                  {:interrupted interrupted
                   :outcome (attr replay :harness/publication-outcome)
                   :settlement (attr replay :harness/settlement)})))]
        (is (= {:interrupted true :outcome "interrupted"
                :settlement "never-launched"}
               result))))))

(deftest public-publication-boundary-shares-the-create-resume-monitor
  (fixture/with-assignment-world
    (fn [ctx]
      (let [result
            (fixture/eval-world
             ctx
             '(do
                (require '[ct.spools.harnesses.catalog :as catalog])
                (let [monitor (catalog/publication-lock rt)
                      entered (promise)
                      finished (promise)
                      worker (Thread.
                              (bound-fn []
                                (let [caller (Thread/currentThread)]
                                  (try
                                    (deliver finished
                                             (harnesses/call-with-run-publication-lock
                                              rt
                                              (fn []
                                                (deliver entered true)
                                                {:thread (= caller (Thread/currentThread))
                                                 :held (Thread/holdsLock monitor)
                                                 :nested
                                                 (harnesses/call-with-run-publication-lock
                                                  rt
                                                  #(attr (harnesses/create!
                                                          rt {:harness :fake :prompt "locked"})
                                                         :harness/publication-outcome))})))
                                    (catch Throwable error
                                      (deliver finished {:error (ex-message error)}))))))
                      blocked
                      (locking monitor
                        (.start worker)
                        ;; Observe actual monitor contention, not a timing-based
                        ;; absence of writes from a possibly unscheduled worker.
                        (let [deadline (+ (System/nanoTime) 5000000000)]
                          (loop []
                            (cond
                              (= Thread$State/BLOCKED (.getState worker))
                              (not (realized? entered))
                              (or (realized? finished)
                                  (> (System/nanoTime) deadline)) false
                              :else (do (Thread/yield) (recur))))))]
                  (.join worker 5000)
                  {:blocked blocked :finished (deref finished 1000 :timeout)
                   :released (harnesses/call-with-run-publication-lock rt (constantly :released))
                   :exception
                   (let [error (ex-info "thunk failure" {})]
                     (try
                       (harnesses/call-with-run-publication-lock rt #(throw error))
                       false
                       (catch Exception actual (identical? error actual))))})))]
        (is (= {:blocked true
                :finished {:thread true :held true :nested "committed"}
                :released :released :exception true}
               result))))))

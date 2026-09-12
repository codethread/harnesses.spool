(ns ct.spools.harnesses.reviewers-test
  "Public authoring, runtime publication, selection, and fanout contracts."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.test.alpha :as test-alpha]))

(reviewers/defreviewer inert-reviewer
  "Remain inert until selected by a module."
  {:seat 'review-a}
  "Inspect the change.")

(defn- world-deps []
  (let [harnesses-root (test-alpha/spool-checkout-root
                        "ct/spools/harnesses.clj")
        identity-root (test-alpha/spool-checkout-root
                       "millhouse/spools/identity.clj")]
    {:deps
     {'ct.spools/harnesses {:local/root (.getCanonicalPath harnesses-root)}
      'millhouse.spools/identity {:local/root (.getCanonicalPath identity-root)}}}))

(def ^:private init-source
  "(require '[millstrand.api.current.alpha :as current]
             '[millstrand.api.runtime.alpha :as runtime])
   (def rt (current/runtime))
   (runtime/module! rt :identity
     {:ns 'millhouse.spools.identity :required? true})
   (runtime/module! rt :harnesses
     {:ns 'ct.spools.harnesses.spool :after [:identity] :required? true})
   (runtime/module! rt :review-definitions
     {:file \"modules/review_definitions.clj\" :after [:harnesses] :required? true})")

(def ^:private definitions-source
  "(ns modules.review-definitions
     (:require [ct.spools.harnesses.reviewers :as reviewers]))
   (reviewers/defreviewer docs
     \"Review docs.\"
     {:seat ['missing-seat 'review-a]
      :labels [\"PR\" \"Docs\"]
      :glob [\"docs/**\"]
      :system-prompt \"Reviewer-specific guidance.\"}
     \"Documentation brief.\")
   (reviewers/defreviewer source
     \"Review source.\"
     {:seat 'review-b :labels [\"PR\" \"Source\"] :glob [\"src/**\"]}
     \"Source brief.\")")

(def ^:private selection-source
  "(ns modules.review-selection
     (:require [ct.spools.harnesses.reviewers :as reviewers]))
   (reviewers/defreviewer! docs
     \"Review docs.\"
     {:seat ['missing-seat 'review-a]
      :labels [\"PR\" \"Docs\"]
      :glob [\"docs/**\"]
      :system-prompt \"Reviewer-specific guidance.\"}
     \"Documentation brief.\")
   (reviewers/defreviewer! source
     \"Review source.\"
     {:seat 'review-b :labels [\"PR\" \"Source\"] :glob [\"src/**\"]}
     \"Source brief.\")")

(defn- with-review-world*
  [selected? f]
  (let [suffix (str/replace (str (random-uuid)) "-" "")
        definitions-ns (str "modules.review-definitions-" suffix)
        selection-ns (str "modules.review-selection-" suffix)
        definitions (str/replace definitions-source
                                 "modules.review-definitions" definitions-ns)
        selection (-> selection-source
                      (str/replace "modules.review-selection" selection-ns)
                      (str/replace "modules.review-definitions" definitions-ns))]
    (test-alpha/with-weaver-world
      [ctx {:storage :sqlite-memory
            :deps-edn (pr-str (world-deps))
            :init-clj (str init-source
                           (when selected?
                             "\n(runtime/module! rt :review-selection {:file \"modules/review_selection.clj\" :after [:review-definitions] :required? true})"))
            :files {"modules/review_definitions.clj" definitions
                    "modules/review_selection.clj" selection}}]
      (f (assoc ctx
                :review-init init-source
                :definitions-ns definitions-ns
                :selection-ns selection-ns)))))

(defn- with-review-world [f]
  (with-review-world* false f))

(def ^:private register-fakes
  '(do
     (require '[ct.spools.harnesses :as harnesses]
              '[millstrand.api.current.alpha :as current])
     (def rt (current/runtime))
     (harnesses/register-harness!
      rt :fake
      {:modes #{:headless}
       :prepare 'ct.spools.harnesses/create!
       :finish 'ct.spools.harnesses/finish!})
     (harnesses/register-alias!
      rt :missing-seat
      {:doc "Known disabled alias." :parent :fake :when :seat/missing
       :attributes {}})
     (harnesses/register-alias!
      rt :review-a
      {:doc "Alias A." :parent :fake :append-system-prompt "Alias guidance A."
       :attributes {}})
     (harnesses/register-alias!
      rt :review-b
      {:doc "Alias B." :parent :fake :append-system-prompt "Alias guidance B."
       :attributes {}})))

(def ^:private register-namespaced-fakes
  '(do
     (require '[ct.spools.harnesses :as harnesses]
              '[millstrand.api.current.alpha :as current])
     (def rt (current/runtime))
     (harnesses/register-harness!
      rt :fake
      {:modes #{:headless}
       :prepare 'ct.spools.harnesses/create!
       :finish 'ct.spools.harnesses/finish!})
     (harnesses/register-alias!
      rt :team/reviewer-keyword
      {:doc "Namespaced keyword alias." :parent :fake :attributes {}})
     (harnesses/register-alias!
      rt 'team/reviewer-symbol
      {:doc "Namespaced symbol alias." :parent :fake :attributes {}})))

(defn- namespaced-definitions-source [definitions-ns]
  (str "(ns " definitions-ns "\n"
       "  (:require [ct.spools.harnesses.reviewers :as reviewers]))\n"
       "(reviewers/defreviewer keyword-seat\n"
       "  \"Use the namespaced keyword seat.\"\n"
       "  {:seat :team/reviewer-keyword}\n"
       "  \"Keyword-seat brief.\")\n"
       "(reviewers/defreviewer symbol-seat\n"
       "  \"Use the namespaced symbol seat.\"\n"
       "  {:seat 'team/reviewer-symbol}\n"
       "  \"Symbol-seat brief.\")\n"))

(deftest authoring-is-inert-and-owner-complete-per-runtime
  (is (= 'inert-reviewer (:name inert-reviewer)))
  (with-review-world
    (fn [ctx]
      (test-alpha/repl! ctx register-fakes)
      (is (= [] (test-alpha/repl!
                 ctx
                 '(do (require '[ct.spools.harnesses.reviewers :as reviewers])
                      (reviewers/reviewers rt)))))
      (spit (io/file (:config-dir ctx) "init.clj")
            (str (:review-init ctx)
                 "\n(runtime/module! rt :review-selection"
                 " {:file \"modules/review_selection.clj\""
                 " :after [:review-definitions] :required? true})"))
      (test-alpha/repl!
       ctx
       '(do
          (require '[millstrand.api.runtime.alpha :as runtime])
          (runtime/refresh! rt)))
      (test-alpha/repl! ctx register-fakes)
      (let [listed (test-alpha/repl!
                    ctx
                    '(do
                       (require '[ct.spools.harnesses.reviewers :as reviewers])
                       (reviewers/reviewers rt)))]
        (is (= ["docs" "source"] (mapv :name listed)))
        (is (= [["PR" "Docs"] ["PR" "Source"]] (mapv :labels listed)))
        (is (= ["review-a" "review-b"] (mapv :selected-seat listed))))
      (spit (io/file (:config-dir ctx) "modules/review_selection.clj")
            (str "(ns " (:selection-ns ctx) "\n"
                 "  (:require [ct.spools.harnesses.reviewers :as reviewers]\n"
                 "            [" (:definitions-ns ctx) " :as definitions]))\n"
                 "(reviewers/use-reviewer! definitions/docs)\n"))
      (test-alpha/repl!
       ctx
       '(do
          (require '[millstrand.api.runtime.alpha :as runtime])
          (runtime/refresh! rt)))
      (test-alpha/repl! ctx register-fakes)
      (is (= ["docs"]
             (test-alpha/repl!
              ctx
              '(do
                 (require '[ct.spools.harnesses.reviewers :as reviewers])
                 (mapv :name (reviewers/reviewers rt)))))))))

(deftest public-reviewer-selection-normalizes-namespaced-seats
  (with-review-world
    (fn [ctx]
      (spit (io/file (:config-dir ctx) "modules/review_definitions.clj")
            (namespaced-definitions-source (:definitions-ns ctx)))
      (spit (io/file (:config-dir ctx) "modules/review_selection.clj")
            (str "(ns " (:selection-ns ctx) "\n"
                 "  (:require [ct.spools.harnesses.reviewers :as reviewers]\n"
                 "            [" (:definitions-ns ctx) " :as definitions]))\n"
                 "(reviewers/use-reviewer! definitions/keyword-seat\n"
                 "                           definitions/symbol-seat)\n"))
      (spit (io/file (:config-dir ctx) "init.clj")
            (str (:review-init ctx)
                 "\n(runtime/module! rt :review-selection"
                 " {:file \"modules/review_selection.clj\""
                 " :after [:review-definitions] :required? true})"))
      (test-alpha/repl! ctx register-namespaced-fakes)
      (test-alpha/repl!
       ctx
       '(do
          (require '[millstrand.api.runtime.alpha :as runtime])
          (runtime/refresh! rt)))
      (test-alpha/repl! ctx register-namespaced-fakes)
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses.execution :as execution]
                         '[ct.spools.harnesses.reviewers :as reviewers])
                (with-redefs [execution/schedule! (fn [_] nil)]
                  (reviewers/start!
                   rt
                   {:git (str "diff --git a/src/a.clj b/src/a.clj\n"
                              "--- a/src/a.clj\n+++ b/src/a.clj\n"
                              "@@ -1 +1 @@\n-old\n+new\n")
                    :agents ["keyword-seat" "symbol-seat"]}))))]
        (is (= ["reviewer-keyword" "reviewer-symbol"]
               (mapv :seat (:runs result))))))))

(deftest public-start-fans-out-ready-runs-and-freezes-context
  (with-review-world
    (fn [ctx]
      (test-alpha/repl! ctx register-fakes)
      (spit (io/file (:config-dir ctx) "init.clj")
            (str (:review-init ctx)
                 "\n(runtime/module! rt :review-selection"
                 " {:file \"modules/review_selection.clj\""
                 " :after [:review-definitions] :required? true})"))
      (test-alpha/repl!
       ctx
       '(do
          (require '[millstrand.api.runtime.alpha :as runtime])
          (runtime/refresh! rt)
          (runtime/refresh! rt)))
      (let [result
            (test-alpha/repl!
             ctx
             '(do
                (require '[ct.spools.harnesses :as harnesses]
                         '[ct.spools.harnesses.execution :as execution]
                         '[ct.spools.harnesses.reviewers :as reviewers]
                         '[millstrand.api.spool.alpha :as spool]
                         '[millstrand.api.weaver.alpha :as weaver])
                (harnesses/register-harness!
                 rt :fake
                 {:modes #{:headless}
                  :prepare 'ct.spools.harnesses/create!
                  :finish 'ct.spools.harnesses/finish!})
                (doseq [[alias prompt] [[:review-a "Alias guidance A."]
                                        [:review-b "Alias guidance B."]]]
                  (harnesses/register-alias!
                   rt alias
                   {:doc "Review alias." :parent :fake
                    :append-system-prompt prompt :attributes {}}))
                (harnesses/register-alias!
                 rt :missing-seat
                 {:doc "Disabled." :parent :fake :when :seat/missing
                  :attributes {}})
                (with-redefs [execution/schedule! (fn [_] nil)]
                  (let [patch (str "diff --git a/docs/a.md b/docs/a.md\n"
                                   "--- a/docs/a.md\n+++ b/docs/a.md\n"
                                   "@@ -1 +1 @@\n-old\n+new\n")
                        result (reviewers/start!
                                rt
                                {:git patch
                                 :agents ["source" "docs" "docs"]})
                        runs (mapv #(harnesses/run rt (:id %)) (:runs result))
                        glob-result (reviewers/start! rt {:git patch})
                        label-result (reviewers/start!
                                      rt {:git patch :labels ["Source"]})
                        cli-result (weaver/op!
                                    rt 'agent
                                    ["review" "--git" ":stdin"
                                     "--agent" "docs" "--agent" "docs"
                                     "--label" "PR"]
                                    {:payloads {"stdin" ""}})]
                    {:result result
                     :glob-result glob-result
                     :label-result label-result
                     :cli-result cli-result
                     :statuses (mapv #(spool/attr-get % :harness/status) runs)
                     :prompts (mapv #(spool/attr-get % :harness/prompt) runs)
                     :systems (mapv #(spool/attr-get
                                      % :harness/appended-system-prompts) runs)
                     :contexts (mapv #(spool/attr-get % :harness/context) runs)}))))]
        (is (= "scheduled" (get-in result [:result :status])))
        (is (= "agent review" (get-in result [:cli-result :operation])))
        (is (= "no-changes" (get-in result [:cli-result :reason])))
        (is (= ["docs" "source"]
               (mapv :reviewer (get-in result [:result :runs]))))
        (is (= ["docs"]
               (mapv :reviewer (get-in result [:glob-result :runs]))))
        (is (= "no-matching-reviewers"
               (get-in result [:label-result :reason])))
        (is (= ["ready" "ready"] (:statuses result)))
        (is (= 2 (count (distinct (map :id (get-in result [:result :runs]))))))
        (is (every? #(re-find #"Authoritative literal unified diff" %)
                    (:prompts result)))
        (is (re-find #"Documentation brief" (first (:prompts result))))
        (is (re-find #"Source brief" (second (:prompts result))))
        (is (= (mapv #(get-in % ["review/change" :diff]) (:contexts result))
               (repeat 2 (get-in result [:contexts 0 "review/change" :diff]))))
        (is (= ["Alias guidance A." "Alias guidance B."]
               (mapv first (:systems result))))
        (is (every? #(re-find #"prompt-level policy" (second %))
                    (:systems result)))
        (is (re-find #"Reviewer-specific guidance"
                     (second (first (:systems result)))))))))

(deftest public-start-validates-before-capture-or-create
  (with-review-world
    (fn [ctx]
      (test-alpha/repl! ctx register-fakes)
      (spit (io/file (:config-dir ctx) "init.clj")
            (str (:review-init ctx)
                 "\n(runtime/module! rt :review-selection"
                 " {:file \"modules/review_selection.clj\""
                 " :after [:review-definitions] :required? true})"))
      (test-alpha/repl!
       ctx
       '(do
          (require '[millstrand.api.runtime.alpha :as runtime])
          (runtime/refresh! rt)))
      (is (= [true true true]
             (test-alpha/repl!
              ctx
              '(do
                 (require '[ct.spools.harnesses :as harnesses]
                          '[ct.spools.harnesses.reviewers :as reviewers])
                 (harnesses/register-harness!
                  rt :fake
                  {:modes #{:headless}
                   :prepare 'ct.spools.harnesses/create!
                   :finish 'ct.spools.harnesses/finish!})
                 (doseq [alias [:review-a :review-b]]
                   (harnesses/register-alias!
                    rt alias {:doc "Review alias." :parent :fake :attributes {}}))
                 (harnesses/register-alias!
                  rt :missing-seat
                  {:doc "Disabled." :parent :fake :when :seat/missing
                   :attributes {}})
                 (mapv
                  (fn [request]
                    (try (reviewers/start! rt request) false
                         (catch clojure.lang.ExceptionInfo _ true)))
                  [{:git "" :agents ["typo"]}
                   {:git "" :labels ["typo"]}
                   {:git "" :target "unsupported"}]))))))))

(def ^:private consumer-kondo-import-command
  ["sh" "-c"
   "clojure -M:lint --lint \"$(clojure -Spath)\" --dependencies --parallel --copy-configs --skip-lint"])

(def ^:private consumer-kondo-lint-command
  ["clojure" "-M:lint" "--lint" "src" "--cache" "false"])

(defn- write-consumer-file! [root relative-path content]
  (let [file (io/file root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)))

(defn- delete-consumer-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (io/delete-file file true)))

(defn- run-consumer-command! [root command]
  (let [{:keys [exit out err]}
        (apply sh/sh (concat command [:dir (.getPath root)]))]
    {:exit exit :output (str out err)}))

(defn- consumer-deps [repository]
  {:paths ["src"]
   :deps {'ct.spools/harnesses {:local/root (.getCanonicalPath repository)}
          'clj-kondo/clj-kondo {:mvn/version "2025.06.05"}}
   :aliases {:lint {:main-opts ["-m" "clj-kondo.main"]}}})

(deftest reviewer-export-imports-and-lints-consumer-macros
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "reviewer-kondo-consumer"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [repository (.getCanonicalFile (io/file "."))
            _ (is (io/resource
                   "clj-kondo.exports/ct.spools/harnesses/config.edn"))]
        (write-consumer-file! root "deps.edn" (pr-str (consumer-deps repository)))
        (.mkdirs (io/file root ".clj-kondo"))
        (write-consumer-file!
         root
         "src/consumer/reviewers.clj"
         (str "(ns consumer.reviewers\n"
              "  \"Consumer reviewer macro proof.\"\n"
              "  (:require [ct.spools.harnesses.reviewers :as reviewers]))\n\n"
              "(reviewers/defreviewer inert-reviewer\n"
              "  \"An inert reviewer declaration.\"\n"
              "  {:seat 'reviewer}\n"
              "  \"Inspect the change.\")\n"
              "(reviewers/defreviewer! selected-reviewer\n"
              "  \"A selected reviewer declaration.\"\n"
              "  {:seat :reviewer}\n"
              "  \"Inspect the change too.\")\n"
              "(reviewers/use-reviewer! inert-reviewer)\n"))
        (let [import-result (run-consumer-command!
                             root consumer-kondo-import-command)
              lint-result (run-consumer-command!
                           root consumer-kondo-lint-command)
              imported-config (io/file
                               root
                               ".clj-kondo/imports/ct.spools/harnesses/config.edn")]
          (is (zero? (:exit import-result)) (:output import-result))
          (is (not (str/includes? (:output import-result) "No configs copied"))
              (:output import-result))
          (is (.isFile imported-config))
          (is (zero? (:exit lint-result)) (:output lint-result))
          (is (not (re-find #"(?mi)^.*:\s+warning:.*$"
                            (:output lint-result)))
              (:output lint-result))
          (is (not (re-find #"(?i)unresolved symbol"
                            (:output lint-result)))
              (:output lint-result))))
      (finally
        (delete-consumer-tree! root)))))

(deftest reviewer-definition-validates-at-the-authoring-boundary
  (testing "unknown options and empty fallback seats"
    (doseq [options [{:seat :review-a :condition :executable} {:seat []}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (eval `(reviewers/defreviewer invalid#
                            "Invalid." ~options "Prompt.")))))))

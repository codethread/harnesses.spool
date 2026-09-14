(ns ct.spools.harnesses.guidance-fixture-hygiene-test
  "Disposable-world lifetime regressions for native guidance profile fixtures."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [ct.spools.harnesses.guidance-test :as guidance-test]
            [millstrand.test.alpha :as test-alpha]))

(defn- create-fixture! [ctx]
  (test-alpha/repl!
   ctx
   (list 'do guidance-test/lifecycle-setup
         '(.getCanonicalPath fixture-dir))))

(deftest guidance-profile-fixtures-follow-world-success-and-failure-lifetimes
  (let [successful-path
        (guidance-test/with-guidance-world create-fixture!)]
    (is (string? successful-path))
    (is (not (.exists (io/file successful-path)))))
  (let [failed-path (atom nil)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"fixture body failed"
         (guidance-test/with-guidance-world
           (fn [ctx]
             (reset! failed-path (create-fixture! ctx))
             (throw (ex-info "fixture body failed" {}))))))
    (is (string? @failed-path))
    (is (not (.exists (io/file @failed-path))))))

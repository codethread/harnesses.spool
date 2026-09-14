(ns ct.spools.harnesses.strict-json-test
  "Strict protocol container grammar regression tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.strict-json :as strict-json]))

(deftest empty-containers-remain-valid
  (is (= {} (strict-json/parse-object! "{}" 1024 "empty object")))
  (is (= {"array" [] "object" {}}
         (strict-json/parse-object! "{\"array\":[],\"object\":{}}"
                                    1024
                                    "empty containers"))))

(deftest trailing-container-commas-are-rejected
  (doseq [source ["{\"a\":1,}"
                  "{\"a\":1,  \n }"
                  "{\"a\":[1,]}"
                  "{\"a\":[1, \n ]}"
                  "{\"a\":{\"b\":2,}}"
                  "{\"a\":[{\"b\":2,}]}"]]
    (testing source
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"trailing comma"
                            (strict-json/parse-object! source 1024
                                                       "protocol evidence"))))))

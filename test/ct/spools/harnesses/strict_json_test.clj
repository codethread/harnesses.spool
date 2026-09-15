(ns ct.spools.harnesses.strict-json-test
  "Strict protocol container grammar regression tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.strict-json :as strict-json]))

(deftest bounded-strict-json-is-canonical
  (is (= {"a" 1 "nested" {"x" "é/\n"}}
         (strict-json/parse-object! "{\"nested\":{\"x\":\"é/\\n\"},\"a\":1}"
                                    1024 "fixture")))
  (is (= "{\"a\":\"é/\",\"z\":1}"
         (strict-json/canonical-json {"z" 1 "a" "é/"})))
  (is (= 64 (count (strict-json/canonical-sha256 ["run" "/tmp" {}]))))
  (doseq [source ["{\"a\":1,\"a\":2}"
                  "{}{}"
                  "{\"a\":1.5}"
                  "{\"a\":\"\\uD800\"}"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (strict-json/parse-object! source 1024 "fixture"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"byte limit"
                        (strict-json/parse-object! "{\"long\":\"value\"}"
                                                   5 "fixture"))))

(deftest empty-containers-remain-valid
  (is (= {} (strict-json/parse-object! "{}" 1024 "empty object")))
  (is (= {"array" [] "object" {}}
         (strict-json/parse-object! "{\"array\":[],\"object\":{}}"
                                    1024
                                    "empty containers"))))

(deftest unicode-escapes-require-ascii-hex-digits
  (is (= {"ascii" "¯" "literal" "λ" "surrogate" "😀"}
         (strict-json/parse-object!
          (str "{\"ascii\":\"\\u00aF\",\"literal\":\"λ\","
               "\"surrogate\":\"\\uD83D\\uDE00\"}")
          1024
          "protocol evidence")))
  (doseq [source ["{\"value\":\"\\u００４１\"}"
                  "{\"value\":\"\\u٠٠٤١\"}"
                  "{\"value\":\"\\u00Ａ1\"}"
                  "{\"\\u００４１\":true}"]]
    (testing source
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"invalid Unicode escape"
                            (strict-json/parse-object! source 1024
                                                       "protocol evidence"))))))

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

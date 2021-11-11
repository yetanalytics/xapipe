(ns com.yetanalytics.xapipe.util.time-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.util.time :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup])
  (:import [java.time Instant]))

(sup/def-ns-check-tests com.yetanalytics.xapipe.util.time)

(deftest normalize-inst-test
  (testing "expected output format"
    (is (= "1970-01-01T00:00:00.000000000Z"
           (normalize-inst Instant/EPOCH)))))

(deftest normalize-stamp-test
  (testing "expected ouput format"
    (are [stamp-in stamp-out]
        (= stamp-out
           (normalize-stamp stamp-out))
      "1970-01-01T00:00:00.000000000Z" "1970-01-01T00:00:00.000000000Z"
      "1970-01-01T00:00:00.000Z"       "1970-01-01T00:00:00.000000000Z"
      "1970-01-01T00:00:00.001Z"       "1970-01-01T00:00:00.001000000Z"
      ;; tests timing in cljs
      "1970-01-01T00:00:00.101010101Z" "1970-01-01T00:00:00.101010101Z"
      "1970-01-01T00:00:00"            "1970-01-01T00:00:00.000000000Z"

      ;; offset
      "2020-03-31T15:12:03+00:00"      "2020-03-31T15:12:03.000000000Z"
      "2020-03-31T15:12:03+05:00"      "2020-03-31T20:12:03.000000000Z"
      ;; stuff below this line not covered by xapi-schema
      ;; hmm
      "1970"                           "1970-01-01T00:00:00.000000000Z"
      "1969-12-31T19:00:00-0500"       "1970-01-01T00:00:00.000000000Z"
      ;; LOUD HMMM
      "19691231T19:00:00-0500"         "1970-01-01T00:00:00.000000000Z")))

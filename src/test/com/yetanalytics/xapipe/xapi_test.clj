(ns com.yetanalytics.xapipe.xapi-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.xapi :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]
            [clojure.spec.test.alpha :as st]))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.xapi
  {:default {sup/stc-opts {:num-tests 10}}})

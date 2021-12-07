(ns com.yetanalytics.xapipe.job.json-test
  (:require [com.yetanalytics.xapipe.job.json :refer :all]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.job.json
  {:default {sup/stc-opts {:num-tests 10}}})

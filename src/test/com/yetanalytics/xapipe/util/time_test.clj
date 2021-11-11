(ns com.yetanalytics.xapipe.util.time-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.util.time :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.util.time
  {:default {sup/stc-opts {:num-tests 10}}})

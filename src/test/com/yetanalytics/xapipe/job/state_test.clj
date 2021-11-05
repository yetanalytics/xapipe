(ns com.yetanalytics.xapipe.job.state-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.job.state :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(sup/deftest-check-ns
  job-state-test
  com.yetanalytics.xapipe.job.state
  {:default {sup/stc-opts {:num-tests 10}}})

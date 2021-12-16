(ns com.yetanalytics.xapipe.client-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.client :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.client
  {;; Don't test stateful ones like this
   com.yetanalytics.xapipe.client/create-store ::sup/skip
   com.yetanalytics.xapipe.client/get-loop ::sup/skip
   com.yetanalytics.xapipe.client/shutdown ::sup/skip
   com.yetanalytics.xapipe.client/init-client ::sup/skip
   :default {sup/stc-opts {:num-tests 1}}})

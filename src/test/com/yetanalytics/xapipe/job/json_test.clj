(ns com.yetanalytics.xapipe.job.json-test
  (:require [com.yetanalytics.xapipe.job.json :refer :all]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.job.json
  {:default {sup/stc-opts {:num-tests 25}}})

(deftest transit-roundtrip-test
  (is
   (= {:foo "bar"
       [{}] :sure}
      (read-transit-str
       (write-transit-str
        {:foo "bar"
         [{}] :sure})))))

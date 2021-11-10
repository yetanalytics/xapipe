(ns com.yetanalytics.xapipe.job.config-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.job.config :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(def minimal-config
  {:source
   {:request-config {:url-base "http://0.0.0.0:8080"
                     :xapi-prefix "/xapi"}}
   :target
   {:request-config {:url-base "http://0.0.0.0:8081"
                     :xapi-prefix "/xapi"}}})

(deftest minimal-config-test
  (testing "produces valid config"
    (is (s/valid? config-spec (ensure-defaults minimal-config))))
  (testing "idempotent"
    (is (= (ensure-defaults
            minimal-config)
           (ensure-defaults
            (ensure-defaults
             minimal-config))))))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.job.config
  {:default {sup/stc-opts {:num-tests 10}}})

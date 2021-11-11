(ns com.yetanalytics.xapipe.job-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.job :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(def minimal-config
  {:source
   {:request-config {:url-base "http://0.0.0.0:8080"
                     :xapi-prefix "/xapi"}}
   :target
   {:request-config {:url-base "http://0.0.0.0:8081"
                     :xapi-prefix "/xapi"}}})

(deftest minimal-config-test
  (testing "sanity check: minimal input config is valid"
    (is (s/valid? :com.yetanalytics.xapipe.job/config
                  minimal-config))))

(deftest init-job-defaults-test
  (testing "given an id and minimal config it constructs a valid job"
    (is (s/valid? job-spec
                  (init-job
                   "foo"
                   minimal-config))))
  (testing "applies defaults"
    (is (= {:id "foo",
            :config
            {:get-buffer-size 10,
             :statement-buffer-size 500,
             :batch-buffer-size 10,
             :batch-timeout 200,
             :source
             {:request-config
              {:url-base "http://0.0.0.0:8080", :xapi-prefix "/xapi"},
              :batch-size 50,
              :backoff-opts {:budget 10000, :max-attempt 10},
              :poll-interval 1000,
              :get-params {:limit 50}},
             :target
             {:request-config
              {:url-base "http://0.0.0.0:8081", :xapi-prefix "/xapi"},
              :batch-size 50,
              :backoff-opts {:budget 10000, :max-attempt 10}},
             :filter {}},
            :state
            {:status :init,
             :cursor "1970-01-01T00:00:00.000000000Z",
             :source {:errors []},
             :target {:errors []},
             :errors [],
             :filter {}}}

           (init-job
            "foo"
            minimal-config)))))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.job
  {:default {sup/stc-opts {:num-tests 10}}})

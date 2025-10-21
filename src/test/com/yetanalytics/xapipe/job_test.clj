(ns com.yetanalytics.xapipe.job-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.job :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

;; Minimal config for each version
(def minimal-config-0
  {:source
   {:request-config {:url-base "http://0.0.0.0:8080"
                     :xapi-prefix "/xapi"}}
   :target
   {:request-config {:url-base "http://0.0.0.0:8081"
                     :xapi-prefix "/xapi"}}})

(def minimal-config-1
  {:source
   {:request-config {:url-base "http://0.0.0.0:8080"
                     :xapi-version "1.0.3"
                     :xapi-prefix "/xapi"}}
   :target
   {:request-config {:url-base "http://0.0.0.0:8081"
                     :xapi-version "1.0.3"
                     :xapi-prefix "/xapi"}}})

(def minimal-job-0
  {:id "foo"
   :config minimal-config-0})

(def minimal-job-1
  {:id "foo"
   :version 1
   :config minimal-config-1})

(deftest minimal-config-test
  (testing "sanity check: minimal input config is valid at current version"
    (is (s/valid? :com.yetanalytics.xapipe.job/config
                  minimal-config-1)))
  (testing "old version is not"
    (is
     (not
      (s/valid? :com.yetanalytics.xapipe.job/config
                minimal-config-0)))))

(deftest upgrade-job-test
  (testing "can upgrade a job from v0 to v1"
    (is (= minimal-job-1
           (upgrade-job minimal-job-0)))))

(deftest init-job-defaults-test
  (testing "given an id and minimal config it constructs a valid job"
    (is (s/valid? job-spec
                  (init-job
                   "foo"
                   minimal-config-1))))
  (testing "applies defaults"
    (is (= {:id "foo",
            :version 1,
            :config
            {:get-buffer-size 10,
             :statement-buffer-size 500,
             :batch-buffer-size 10,
             :batch-timeout 200,
             :cleanup-buffer-size 50,
             :source
             {:request-config
              {:url-base "http://0.0.0.0:8080",
               :xapi-prefix "/xapi",
               :xapi-version "1.0.3"},
              :batch-size 50,
              :backoff-opts {:budget 10000, :max-attempt 10},
              :poll-interval 1000,
              :get-params {:limit 50}},
             :target
             {:request-config
              {:url-base "http://0.0.0.0:8081",
               :xapi-prefix "/xapi"
               :xapi-version "1.0.3"},
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
            minimal-config-1)))))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.job
  {:default {sup/stc-opts {:num-tests 10}}})

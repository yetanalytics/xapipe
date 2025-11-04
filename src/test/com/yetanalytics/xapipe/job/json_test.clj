(ns com.yetanalytics.xapipe.job.json-test
  (:require [com.yetanalytics.xapipe.job.json :refer :all]
            [com.yetanalytics.xapipe.job :as job]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]
            [clojure.spec.alpha :as s]))

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

(def complex-job
  {:id "foo",
   :version 1
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
      :xapi-prefix "/xapi",
      :xapi-version "1.0.3"},
     :batch-size 50,
     :backoff-opts {:budget 10000, :max-attempt 10}},
    :filter
    {:pattern
     {:profile-urls ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
      :pattern-ids []}}},
   :state
   {:status :init,
    :cursor "1970-01-01T00:00:00.000000000Z",
    :source {:errors []},
    :target {:errors []},
    :errors [],
    :filter {:pattern
             {:accepts [],
              :rejects [],
              :states-map
              {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
               {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                #{{:state 0, :accepted? false}}}}}}}})

(deftest complex-job-test
  (testing "Packs + unpacks a complex job"
    (testing "input sanity"
      (is (nil? (s/explain-data job/job-spec complex-job))))
    (testing "Roundtrip"
      (let [roundtripped (-> complex-job job->json json->job)]
        (is (= complex-job
               roundtripped))))))

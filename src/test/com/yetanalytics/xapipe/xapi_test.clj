(ns com.yetanalytics.xapipe.xapi-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.xapi :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen])
  (:import [java.io File]))

(sup/def-ns-check-tests
  com.yetanalytics.xapipe.xapi
  {:default {sup/stc-opts {:num-tests 10}}})

(deftest response->statements-test
  (testing "deletes extra attachments"
    (let [attachment
          (sgen/generate
           (s/gen :com.yetanalytics.xapipe.client.multipart-mixed/attachment))]
      (response->statements {:body
                             {:statement-result {:statements []}
                              :attachments [attachment]}})
      (is (false? (.exists (:tempfile attachment)))))))

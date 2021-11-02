(ns com.yetanalytics.xapipe.main-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.main :refer :all]
            [com.yetanalytics.xapipe.test-support :as support]))

(use-fixtures :each (partial support/source-target-fixture
                             {:seed-path "dev-resources/lrs/after_conf.edn"}))

(deftest start-test
  (testing "start initializes and runs a job"
    (let [[since until] (support/lrs-stored-range support/*source-lrs*)]
      (is (-> (main* ;; we test this because it doesn't exit!
               "--source-url" (format "http://0.0.0.0:%d/xapi"
                                      (:port support/*source-lrs*))
               "--target-url" (format "http://0.0.0.0:%d/xapi"
                                      (:port support/*target-lrs*))
               "-p" (format "since=%s" since)
               "-p" (format "until=%s" until))
              :status
              (= 0)))
      (is (= 452 (support/lrs-count support/*target-lrs*))))))

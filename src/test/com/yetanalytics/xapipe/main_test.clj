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
               "start"
               (format "http://0.0.0.0:%d/xapi"
                       (:port support/*source-lrs*))
               (format "http://0.0.0.0:%d/xapi"
                       (:port support/*target-lrs*))
               "-p" (format "since=%s" since)
               "-p" (format "until=%s" until))
              :status
              (= 0))))))

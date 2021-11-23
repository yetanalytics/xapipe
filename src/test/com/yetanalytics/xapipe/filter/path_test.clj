(ns com.yetanalytics.xapipe.filter.path-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.filter.path :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(sup/def-ns-check-tests com.yetanalytics.xapipe.filter.path
  ;; TODO: These tests are very slow. Investigate pathetic's gens
  {:default {sup/stc-opts {:num-tests 1}}})

(deftest path-filter-pred-test
  (let [statements (read-string (slurp "dev-resources/statements/calibration_50.edn"))

        ;; Records to pass through the predicate
        records (map #(hash-map :statement %)
                     statements)]
    (testing "excludes missing"
      (let [;; Generated statements have no stored
            pred (path-filter-pred {:ensure-paths [
                                                   [["stored"]]
                                                   ]})]
        (is (= [] (filter pred records)))))
    (testing "includes present"
      (let [pred (path-filter-pred {:ensure-paths [
                                                   [["id"]]
                                                   ]})]
        (is (= records (filter pred records)))))
    (testing "include intersection"
      (let [pred (path-filter-pred {:ensure-paths [
                                                   [["id"]]
                                                   [["timestamp"]]
                                                   ]})]
        (is (= records (filter pred records)))))
    (testing "can match"
      (let [pred (path-filter-pred
                  {:match-paths [
                                 [[["id"]]
                                  "66ff4283-a6ca-439c-b8fe-38ca398271e5"]
                                 ]})]
        (is (= 1 (count (filter pred records))))))
    (testing "can match intersection"
      (let [pred (path-filter-pred
                  {:match-paths [
                                 [[["id"]]
                                  "66ff4283-a6ca-439c-b8fe-38ca398271e5"]
                                 [[["verb"]["id"]]
                                  "https://xapinet.org/xapi/yet/calibration/v1/concepts#didnt"]
                                 ]})]
        (is (= 1 (count (filter pred records))))))

    (testing "can match union"
      (let [pred (path-filter-pred
                  {:match-paths [
                                 [[["id"]]
                                  "66ff4283-a6ca-439c-b8fe-38ca398271e5"]
                                 [[["id"]]
                                  "ee309867-15af-4c76-87b4-5fb49d687ee9"]
                                 ]})]
        (is (= 2 (count (filter pred records))))))))

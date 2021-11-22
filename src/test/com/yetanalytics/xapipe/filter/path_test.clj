(ns com.yetanalytics.xapipe.filter.path-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.filter.path :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(sup/def-ns-check-tests com.yetanalytics.xapipe.filter.path
  ;; TODO: These tests are very slow. Investigate pathetic's gens
  {:default {sup/stc-opts {:num-tests 1}}})

(deftest path-filter-pred-test
  (let [records (map #(hash-map :statement %)
                     (sup/gen-statements 50))]
    (testing "excludes missing"
      (let [;; Generated statements have no stored
            pred (path-filter-pred {:ensure-paths [[["stored"]]]})]
        (is (= [] (filter pred records)))))
    (testing "includes present"
      (let [pred (path-filter-pred {:ensure-paths [[["id"]]]})]
        (is (= records (filter pred records)))))))

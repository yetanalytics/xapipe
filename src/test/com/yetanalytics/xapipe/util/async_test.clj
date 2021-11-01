(ns com.yetanalytics.xapipe.util.async-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.util.async :refer :all]
            [clojure.core.async :as a]))

(deftest batch-filter-test
  (let [a-chan (a/chan 1000)
        b-chan (a/chan 20)
        _ (batch-filter
           a-chan
           b-chan
           50
           500)]
    (a/onto-chan! a-chan (range 1000))
    (let [batches (a/<!! (a/into [] b-chan))]
      (testing "returns all records in order w/o filters"
        (is (= (range 1000)
               (mapcat :batch batches))))
      (testing "Limits batch size"
        (is
         (every?
          (comp (partial >= 50) count :batch)
          batches)))))
  (testing "With stateless predicates"
    (let [a-chan (a/chan 1000)
          b-chan (a/chan 20)
          _ (batch-filter
             a-chan
             b-chan
             50
             500
             :predicates
             {:odd? odd?})]
      (a/onto-chan! a-chan (range 1000))
      (let [batches (a/<!! (a/into [] b-chan))]
        (testing "returns matching records in order"
          (is (= (filter odd? (range 1000))
                 (mapcat :batch batches))))))
    (testing "Must pass all"
      (let [a-chan (a/chan 1000)
            b-chan (a/chan 20)
            _ (batch-filter
               a-chan
               b-chan
               50
               500
               :predicates
               ;; impossible!
               {:odd? odd?
                :even? even?})]
        (a/onto-chan! a-chan (range 1000))
        (let [batches (a/<!! (a/into [] b-chan))]
          (testing "returns nothing"
            (is (= []
                   (mapcat :batch batches)))))))))

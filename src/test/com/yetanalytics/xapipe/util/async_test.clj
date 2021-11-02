(ns com.yetanalytics.xapipe.util.async-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.util.async :refer :all]
            [clojure.core.async :as a]
            [com.yetanalytics.xapipe.test-support :as support]))

;; TODO: this seems to be doing more than checking args. maybe doesn't work yet
#_(use-fixtures :once (support/instrument-fixture
                     `[batch-filter]))

(deftest batch-filter-test
  (testing "with no predicates"
    (let [a-chan (a/chan 1000)
          b-chan (a/chan 20)
          dropped (atom [])
          _ (batch-filter
             a-chan
             b-chan
             50
             500
             :cleanup-fn (fn [rec] (swap! dropped conj rec)))]

      (a/onto-chan! a-chan (range 1000))

      (let [batches (a/<!! (a/into [] b-chan))]
        (testing "returns all records in order w/o filters"
          (is (= (range 1000)
                 (mapcat :batch batches))))
        (testing "Limits batch size"
          (is
           (every?
            (comp (partial >= 50) count :batch)
            batches)))
        (testing "empty states"
          (is
           (every?
            (fn [{:keys [filter-state]}]
              (empty? filter-state))
            batches)))
        (testing "no dropped"
          (is (= []
                 @dropped))
          (is (= 0
                 (count @dropped)))))))

  (testing "With stateless predicates"
    (let [a-chan (a/chan 1000)
          b-chan (a/chan 20)
          dropped (atom [])
          _ (batch-filter
             a-chan
             b-chan
             50
             500
             :stateless-predicates
             {:odd? odd?}
             :cleanup-fn (fn [rec] (swap! dropped conj rec)))]

      (a/onto-chan! a-chan (range 1000))

      (let [batches (a/<!! (a/into [] b-chan))]
        (testing "returns matching records in order"
          (is (= (filter odd? (range 1000))
                 (mapcat :batch batches))))
        (testing "Cleans up"
          (is (= 500 (count @dropped))))
        (testing "empty states"
          (is
           (every?
            (fn [{:keys [filter-state]}]
              (empty? filter-state))
            batches)))))
    (testing "Must pass all"
      (let [a-chan (a/chan 1000)
            b-chan (a/chan 20)
            _ (batch-filter
               a-chan
               b-chan
               50
               500
               :stateless-predicates
               ;; impossible!
               {:odd? odd?
                :even? even?})]

        (a/onto-chan! a-chan (range 1000))

        (let [batches (a/<!! (a/into [] b-chan))]
          (testing "returns nothing"
            (is (= []
                   (mapcat :batch batches))))))))
  (testing "With stateful predicates"
    (let [limit-pred (fn [n v]
                       (if (= n 500)
                         [n false]
                         [(inc n) true]))
          a-chan (a/chan 1000)
          b-chan (a/chan 20)
          dropped (atom [])
          _ (batch-filter
             a-chan
             b-chan
             50
             500
             :stateful-predicates
             {:limit limit-pred}
             :init-states
             {:limit 0}
             :cleanup-fn (fn [rec] (swap! dropped conj rec)))]

      (a/onto-chan! a-chan (range 1000))

      (let [batches (a/<!! (a/into [] b-chan))]
        (testing "returns matching records in order"
          (is (= 500
                 (count (mapcat :batch batches)))))
        (testing "returns states"
          (is (= (range 50 501 50)
                 (map #(get-in % [:filter-state :limit])
                      batches))))
        (testing "Cleans up"
          (is (= 500 (count @dropped))))))))

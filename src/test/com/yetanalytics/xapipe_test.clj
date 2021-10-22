(ns com.yetanalytics.xapipe-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [com.yetanalytics.lrs.test-runner :as test-runner]
            [com.yetanalytics.xapipe :refer :all]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem]
            [com.yetanalytics.xapipe.test-support :as support])
  (:import [java.time Instant]))

(use-fixtures :once test-runner/test-suite-fixture)
(use-fixtures :each support/source-target-fixture)

(deftest xfer-test
  (testing "xapipe transfers conf test data from source to target"
    ;; Seed source with data
    (support/seed-conf-tests! support/*source-lrs*)
    ;; Make sure it's in there
    (is (= 453 (support/lrs-count support/*source-lrs*)))
    (let [[since until] (support/lrs-stored-range support/*source-lrs*)
          _ (log/infof "since: %s until: %s" since until)
          config {:source
                  {:request-config (:request-config support/*source-lrs*)
                   :get-params     {:since since
                                    :until until}
                   :poll-interval  1000
                   :batch-size     50}
                  :target
                  {:request-config (:request-config support/*target-lrs*)
                   :batch-size     50}}
          ;; Memory Store
          store (mem/new-store)
          ;; Generate an ID
          job-id (.toString (java.util.UUID/randomUUID))
          _ (log/info "Starting transfer...")
          ;; Run the transfer
          stop-fn (run-job store job-id config)]
      (while (-> (store/get-job store job-id)
                 :state
                 :status
                 #{:init :running})
        (log/info "Transfer in progress...")
        (Thread/sleep 1000))
      ;; At this point we're done or have errored.
      (let [{{:keys [status
                     cursor]} :state
             :as job} (store/get-job store job-id)]
        (when (= status :error)
          (log/error "Job Error" job))
        (testing "successful completion"
          (is (= :complete status)))
        (testing "all statements transferred except empty ref"
          (is (= 452 (support/lrs-count support/*target-lrs*))))
        (testing "read up to end"
          (is (= (Instant/parse until) (Instant/parse cursor))))
        (testing "matching statement ids and order"
          (let [source-idset (into #{} (support/lrs-ids support/*source-lrs*))]
            (is (every? #(contains? source-idset %)
                        (support/lrs-ids support/*target-lrs*)))))))))

(ns com.yetanalytics.xapipe-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [com.yetanalytics.lrs.test-runner :as test-runner]
            [com.yetanalytics.xapipe :refer :all]
            [com.yetanalytics.xapipe.job :as job]
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
          ;; Generate an ID
          job-id (.toString (java.util.UUID/randomUUID))
          ;; Initialize
          job (job/init-job
               job-id
               config)
          _ (log/info "Starting transfer...")
          ;; Run the transfer
          {:keys [stop-fn states]} (run-job job)
          ;; Get all the states
          all-states (a/<!! (a/go-loop [acc []]
                              (if-let [state (a/<! states)]
                                (do
                                  (log/info "state" state)
                                  (recur (conj acc state)))
                                acc)))]
      ;; At this point we're done or have errored.
      (let [{:keys [status
                    cursor]} (last all-states)]
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

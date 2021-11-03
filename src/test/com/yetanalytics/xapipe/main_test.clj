(ns com.yetanalytics.xapipe.main-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.cli :as cli]
            [com.yetanalytics.xapipe.main :refer :all]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem]
            [com.yetanalytics.xapipe.test-support :as sup]))

(deftest start-test
  (sup/with-running [source (sup/lrs
                             :seed-path
                             "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "cli runs job with minimal args"
      (let [[since until] (sup/lrs-stored-range source)]
        (is (-> (main* ;; we test this because it doesn't exit!
                 "--source-url" (format "http://0.0.0.0:%d/xapi"
                                        (:port source))
                 "--target-url" (format "http://0.0.0.0:%d/xapi"
                                        (:port target))
                 "-p" (format "since=%s" since)
                 "-p" (format "until=%s" until))
                :status
                (= 0)))
        (is (= 452 (sup/lrs-count target)))))))

(deftest resume-test
  (sup/with-running [source (sup/lrs
                             :seed-path
                             "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (let [[since until] (sup/lrs-stored-range source)
          ;; not what we'd normally get
          {:keys [states
                  stop-fn
                  store]}
          (with-redefs [;; We hijack the internal handle-job
                        ;; fn so we can see what happens
                        cli/handle-job (fn [store job client-opts]
                                         (assoc
                                          (xapipe/run-job job client-opts)
                                          ;; Attach the store for possible
                                          ;; inspection who knows
                                          :store store))]
            (main*
             "--source-url" (format "http://0.0.0.0:%d/xapi"
                                    (:port source))
             "--target-url" (format "http://0.0.0.0:%d/xapi"
                                    (:port target))
             "-p" (format "since=%s" since)
             "-p" (format "until=%s" until)
             ;; use an atom we can DUMP
             "-s" "mem"))
          ;; take 2 states so some communication might happen
          _ (a/<!! (a/into [] (a/take 2 states)))
          ;; Wait for it
          _ (Thread/sleep 1000)
          ;; SHUT IT DOWN
          _ (stop-fn)
          ;; What remains?
          tail-states (a/<!! (a/into [] states))
          job-id (-> tail-states first :id)]
      (testing "make sure it is paused"
        (is (= [:running :paused]
               (map #(get-in % [:state :status])
                    tail-states))))
      (testing "should have done about a batch"
        (is (= 50
               (sup/lrs-count target))))
      ;; Go ahead and stick it in the store now
      (store/write-job store (last tail-states))
      (is (= {job-id
              {:id job-id,
               :config
               {:get-buffer-size 10,
                :statement-buffer-size 500,
                :batch-buffer-size 10,
                :batch-timeout 200,
                :source
                {:request-config
                 {:url-base (format "http://0.0.0.0:%s"
                                    (:port source)),
                  :xapi-prefix "/xapi"},
                 :get-params
                 {:since "2021-10-25T15:05:00.537746000Z",
                  :until "2021-10-25T15:05:32.595885000Z",
                  :limit 50},
                 :poll-interval 1000,
                 :batch-size 50,
                 :backoff-opts {:budget 10000, :max-attempt 10}},
                :target
                {:request-config
                 {:url-base (format "http://0.0.0.0:%d"
                                    (:port target)),
                  :xapi-prefix "/xapi"},
                 :batch-size 50,
                 :backoff-opts {:budget 10000, :max-attempt 10}},
                :filter {}},
               :state
               {:status :paused,
                :cursor "2021-10-25T15:05:01.539629Z",
                :source {:errors []},
                :target {:errors []},
                :errors [],
                :filter {}}}}
             (mem/dump store)))
      ;; Resume from cli
      (testing "xapipe resumes a paused job"
        (let [job-id (-> tail-states last :id)]
          ;; Here we override create-store to return the same store
          ;; thus simultating proper persistence like redis
          (is (-> (with-redefs [cli/create-store (constantly store)]
                    (main*
                     "--job-id" job-id
                     "-s" "mem"))
                  :status
                  (= 0)))
          (is (= 452 (sup/lrs-count target))))))))

(deftest force-resume-test
  (sup/with-running [source (sup/lrs
                             :seed-path
                             "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (let [[since until] (sup/lrs-stored-range source)
          ;; not what we'd normally get
          {:keys [states
                  stop-fn
                  store]}
          (with-redefs [;; We hijack the internal handle-job
                        ;; fn so we can see what happens
                        cli/handle-job (fn [store job client-opts]
                                         (assoc
                                          (xapipe/run-job job client-opts)
                                          ;; Attach the store for possible
                                          ;; inspection who knows
                                          :store store))]
            (main*
             "--source-url" (format "http://0.0.0.0:%d/xapi"
                                    (:port source))
             ;; Pass a bad target
             "--target-url" "http://0.0.0.0:1234/xapi"
             "-p" (format "since=%s" since)
             "-p" (format "until=%s" until)
             ;; use an atom we can DUMP
             "-s" "mem"))
          ;; take 2 states so some communication might happen
          _ (a/<!! (a/into [] (a/take 2 states)))
          ;; Wait for it
          _ (Thread/sleep 1000)
          ;; What remains?
          tail-states (a/<!! (a/into [] states))
          job-id (-> tail-states first :id)]
      (testing "make sure it is errored"
        (is (= [:error]
               (map #(get-in % [:state :status])
                    tail-states))))
      ;; Go ahead and stick it in the store now
      (store/write-job store (last tail-states))
      (is (= {job-id
              {:id job-id,
               :config
               {:get-buffer-size 10,
                :statement-buffer-size 500,
                :batch-buffer-size 10,
                :batch-timeout 200,
                :source
                {:request-config
                 {:url-base (format "http://0.0.0.0:%d"
                                    (:port source)),
                  :xapi-prefix "/xapi"},
                 :get-params
                 {:since "2021-10-25T15:05:00.537746000Z",
                  :until "2021-10-25T15:05:32.595885000Z",
                  :limit 50},
                 :poll-interval 1000,
                 :batch-size 50,
                 :backoff-opts {:budget 10000, :max-attempt 10}},
                :target
                {:request-config
                 {:url-base "http://0.0.0.0:1234", :xapi-prefix "/xapi"},
                 :batch-size 50,
                 :backoff-opts {:budget 10000, :max-attempt 10}},
                :filter {}},
               :state
               {:status :error,
                :cursor "2021-10-25T15:05:00.537746000Z",
                :source {:errors []},
                :target {:errors [{:type :target, :message "Connection refused"}]},
                :errors [],
                :filter {}}}}
             (mem/dump store)))
      (with-redefs [cli/create-store (constantly store)]
        (testing "xapipe normally can't resume with errors"
          (is (= 1
                 (:status
                  (main*
                   "--job-id" job-id
                   "-s" "mem"
                   "--target-url" (format "http://0.0.0.0:%d/xapi"
                                          (:port target)))))))
        (testing "xapipe force resumes an errored job"
          (is (= 0
                 (:status
                  (main*
                   "--job-id" job-id
                   "-s" "mem"
                   "--target-url" (format "http://0.0.0.0:%d/xapi"
                                          (:port target))
                   "-f" ;; force arg
                   ))))
          (is (= 452 (sup/lrs-count target))))))))

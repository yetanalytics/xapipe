(ns com.yetanalytics.xapipe.main-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.main :refer :all]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem]
            [com.yetanalytics.xapipe.test-support :as sup]))

(deftest create-store-test
  (is (satisfies? store/XapipeStore (create-store {:storage :noop})))
  (is (satisfies? store/XapipeStore (create-store {:storage :redis
                                                   :redis-host "localhost"
                                                   :redis-port 1234})))
  (is (satisfies? store/XapipeStore (create-store {:storage :mem}))))

(deftest parse-lrs-url-test
  (is (= {:url-base "http://0.0.0.0:8080", :xapi-prefix "/xapi"}
         (parse-lrs-url "http://0.0.0.0:8080/xapi"))))

(deftest options->client-opts-test
  (is (= {:conn-mgr-opts
          {:timeout 10,
           :threads 4,
           :default-per-route 2,
           :io-config {:io-thread-count 16}}}
         (options->client-opts
          {:conn-timeout 10
           :conn-threads 4
           :conn-default-per-route 2
           :conn-insecure? false
           :conn-io-thread-count 16}))))

(deftest options->config-test
  (is (= {:get-buffer-size 100,
          :batch-timeout 200,
          :source
          {:request-config
           {:url-base "http://0.0.0.0:8080",
            :xapi-prefix "/xapi",
            :username "foo",
            :password "bar"},
           :get-params {},
           :poll-interval 1000,
           :batch-size 50,
           :backoff-opts
           {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
          :target
          {:request-config
           {:url-base "http://0.0.0.0:8081",
            :xapi-prefix "/xapi",
            :username "foo",
            :password "bar"},
           :batch-size 50,
           :backoff-opts
           {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
          :filter {},
          :statement-buffer-size 1000,
          :batch-buffer-size 100}
         (options->config
          {:job-id "foo"

           :source-batch-size 50
           :source-poll-interval 1000
           :get-params {}
           :source-username "foo"
           :source-password "bar"
           :source-backoff-budget 1000
           :source-backoff-max-attempt 10
           :source-backoff-j-range 10
           :source-backoff-initial 1

           :target-batch-size 50
           :target-username "foo"
           :target-password "bar"
           :target-backoff-budget 1000
           :target-backoff-max-attempt 10
           :target-backoff-j-range 10
           :target-backoff-initial 1

           :get-buffer-size 100
           :batch-timeout 200

           :filter-template-profile-urls []
           :filter-template-ids []
           :filter-pattern-profile-urls []
           :filter-pattern-ids []

           :statement-buffer-size 1000
           :batch-buffer-size 100}
          {:url-base "http://0.0.0.0:8080", :xapi-prefix "/xapi"}
          {:url-base "http://0.0.0.0:8081", :xapi-prefix "/xapi"}))))

(deftest create-job-test
  (is (= {:id "foo",
          :config
          {:get-buffer-size 100,
           :statement-buffer-size 1000,
           :batch-buffer-size 100,
           :batch-timeout 200,
           :source
           {:request-config
            {:url-base "http://0.0.0.0:8080",
             :xapi-prefix "/xapi",
             :username "foo",
             :password "bar"},
            :get-params {:limit 50},
            :poll-interval 1000,
            :batch-size 50,
            :backoff-opts
            {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
           :target
           {:request-config
            {:url-base "http://0.0.0.0:8081",
             :xapi-prefix "/xapi",
             :username "foo",
             :password "bar"},
            :batch-size 50,
            :backoff-opts
            {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
           :filter {}},
          :state
          {:status :init,
           :cursor "1970-01-01T00:00:00Z",
           :source {:errors []},
           :target {:errors []},
           :errors [],
           :filter {}}}
         (create-job
          {:job-id "foo"

           :source-url "http://0.0.0.0:8080/xapi"
           :source-batch-size 50
           :source-poll-interval 1000
           :get-params {}
           :source-username "foo"
           :source-password "bar"
           :source-backoff-budget 1000
           :source-backoff-max-attempt 10
           :source-backoff-j-range 10
           :source-backoff-initial 1

           :target-url "http://0.0.0.0:8081/xapi"
           :target-batch-size 50
           :target-username "foo"
           :target-password "bar"
           :target-backoff-budget 1000
           :target-backoff-max-attempt 10
           :target-backoff-j-range 10
           :target-backoff-initial 1

           :get-buffer-size 100
           :batch-timeout 200

           :filter-template-profile-urls []
           :filter-template-ids []
           :filter-pattern-profile-urls []
           :filter-pattern-ids []

           :statement-buffer-size 1000
           :batch-buffer-size 100}))))

(deftest reconfigure-job-test
  (is (= {:id "foo",
          :config
          {:get-buffer-size 100,
           :statement-buffer-size 1000,
           :batch-buffer-size 100,
           :batch-timeout 200,
           :source
           {:request-config
            {:url-base "http://0.0.0.0:8082",
             :xapi-prefix "/xapi2",
             :username "baz",
             :password "quxx"},
            :get-params {:format "exact"},
            :poll-interval 3000,
            :batch-size 100,
            :backoff-opts
            {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
           :target
           {:request-config
            {:url-base "http://0.0.0.0:8083",
             :xapi-prefix "/xapi2",
             :username "baz",
             :password "quxx"},
            :batch-size 100,
            :backoff-opts
            {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
           :filter {}},
          :state
          {:status :init,
           :cursor "1970-01-01T00:00:00Z",
           :source {:errors []},
           :target {:errors []},
           :errors [],
           :filter {}},
          :get-buffer-size 200,
          :batch-timeout 300,
          :statement-buffer-size 10000,
          :batch-buffer-size 1000}
         (reconfigure-job
          {:id "foo",
           :config
           {:get-buffer-size 100,
            :statement-buffer-size 1000,
            :batch-buffer-size 100,
            :batch-timeout 200,
            :source
            {:request-config
             {:url-base "http://0.0.0.0:8080",
              :xapi-prefix "/xapi",
              :username "foo",
              :password "bar"},
             :get-params {:limit 50},
             :poll-interval 1000,
             :batch-size 50,
             :backoff-opts
             {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
            :target
            {:request-config
             {:url-base "http://0.0.0.0:8081",
              :xapi-prefix "/xapi",
              :username "foo",
              :password "bar"},
             :batch-size 50,
             :backoff-opts
             {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
            :filter {}},
           :state
           {:status :init,
            :cursor "1970-01-01T00:00:00Z",
            :source {:errors []},
            :target {:errors []},
            :errors [],
            :filter {}}}
          {:job-id "foo"
           :source-url "http://0.0.0.0:8082/xapi2"
           :source-batch-size 100
           :source-poll-interval 3000
           :get-params {:format "exact"}
           :source-username "baz"
           :source-password "quxx"

           :target-url "http://0.0.0.0:8083/xapi2"
           :target-batch-size 100
           :target-username "baz"
           :target-password "quxx"

           :get-buffer-size 200
           :batch-timeout 300

           :statement-buffer-size 10000
           :batch-buffer-size 1000}))))

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
                        handle-job (fn [store job client-opts]
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
                  :until "2021-10-25T15:05:32.595885000Z"},
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
                :filter {}},
               :get-buffer-size 10,
               :batch-timeout 200}}
             (mem/dump store)))
      ;; Resume from cli
      (testing "xapipe resumes a paused job"
        (let [job-id (-> tail-states last :id)]
          ;; Here we override create-store to return the same store
          ;; thus simultating proper persistence like redis
          (is (-> (with-redefs [create-store (constantly store)]
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
                        handle-job (fn [store job client-opts]
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
                   :until "2021-10-25T15:05:32.595885000Z"},
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
                 :filter {}},
                :get-buffer-size 10,
                :batch-timeout 200}}
             (mem/dump store)))
      (with-redefs [create-store (constantly store)]
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

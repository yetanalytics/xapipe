(ns com.yetanalytics.xapipe.main-test
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.cli :as cli]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.json :as jj]
            [com.yetanalytics.xapipe.main :refer :all]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem]
            [com.yetanalytics.xapipe.test-support :as sup])
  (:import [java.io File]))

(use-fixtures :once (sup/instrument-fixture))

(deftest start-test
  (sup/with-running [source (sup/lrs
                             :seed-path
                             "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "cli runs job with minimal args"
      (let [[since until] (sup/lrs-stored-range source)]
        (is (-> (main* ;; we test this because it doesn't exit!
                 "-s" "noop"
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
                        cli/handle-job (fn [store job client-opts _]
                                         (assoc
                                          (xapipe/run-job job
                                                          :client-opts
                                                          client-opts)
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
          ;; take init state so comms happens
          _ (a/<!! states)
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
                :cleanup-buffer-size 50,
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
                :cursor "2021-10-25T15:05:01.539629000Z",
                :source {:errors []},
                :target {:errors []},
                :errors [],
                :filter {}}}}
             (update-in (mem/dump store) [job-id :state] dissoc :updated)))
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
                        cli/handle-job (fn [store job client-opts _]
                                         (assoc
                                          (xapipe/run-job job
                                                          :client-opts
                                                          client-opts)
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
          ;; take init state so comms happens
          _ (a/<!! states)
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
                :cleanup-buffer-size 50,
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
                :target {:errors [{:type :target, :message "Max retries reached: Connection refused"}]},
                :errors [],
                :filter {}}}}
             (update-in (mem/dump store) [job-id :state] dissoc :updated)))
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

(deftest force-resume-complete-test
  (let [common-store (mem/new-store)]
    (with-redefs [cli/create-store (constantly common-store)]
      (sup/with-running [source (sup/lrs
                                 :seed-path
                                 "dev-resources/lrs/after_conf.edn")
                         target (sup/lrs)]

        (let [[since until] (sup/lrs-stored-range source)]
          (testing "original job"
            (is (-> (main*
                     "-s" "mem"
                     "--job-id" "force-resume-complete-test-job"
                     "--source-url" (format "http://0.0.0.0:%d/xapi"
                                            (:port source))
                     "--target-url" (format "http://0.0.0.0:%d/xapi"
                                            (:port target))
                     "-p" (format "since=%s" since)
                     "-p" (format "until=%s" until))
                    :status
                    (= 0)))
            (is (= 452 (sup/lrs-count target))))
          (testing "Can force resume a completed job..."
            (is (-> (main*
                     "-s" "mem"
                     "-f" ;; force
                     "--job-id" "force-resume-complete-test-job")
                    :status
                    (= 0)))
            (testing "But it won't do much because of the cursor"
              (is (= 452 (sup/lrs-count target))))))))))

(deftest json-test
  (sup/with-running [source (sup/lrs
                             :seed-path
                             "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "cli runs job with minimal args"
      (let [job-id "foo"
            [since until] (sup/lrs-stored-range source)
            job (job/init-job
                 job-id
                 {:source
                  {:request-config (:request-config source)
                   :get-params     {:since since
                                    :until until}}
                  :target
                  {:request-config (:request-config target)}})]
        (is (-> (main*
                 "-s" "noop"
                 "--json" (jj/job->json job))
                :status
                (= 0)))
        (is (= 452 (sup/lrs-count target)))))))

(deftest json-file-test
  (sup/with-running [source (sup/lrs
                             :seed-path
                             "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "cli runs job with minimal args"
      (let [job-id "foo"
            [since until] (sup/lrs-stored-range source)
            ^File tempfile (File/createTempFile "xapipe" "test")]
        (try
          ;; Put init job in the file
          (testing "Write a JSON job from args"
            (is (-> (main*
                     "-s" "noop"
                     "--source-url" (format "http://0.0.0.0:%d/xapi"
                                            (:port source))
                     "--target-url" (format "http://0.0.0.0:%d/xapi"
                                            (:port target))
                     "-p" (format "since=%s" since)
                     "-p" (format "until=%s" until)
                     "--json-out" (.getPath tempfile))
                    :status
                    (= 0))))
          (testing "Run a job off of it"
            (is (-> (main*
                     "-s" "noop"
                     "--json-file" (.getPath tempfile))
                    :status
                    (= 0)))
            (is (= 452 (sup/lrs-count target))))
          (finally
            (.delete tempfile)))))))

(deftest file-store-test
  (sup/with-running [source (sup/lrs
                             :seed-path
                             "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (try
      (.delete ^File (io/file ".test_store/foo.edn"))
      (.delete ^File (io/file ".test_store"))
      (let [[since until] (sup/lrs-stored-range source)
            job-id "foo"]
        (testing "runs against the file store"
          (is (= 0
                 (:status
                  (main*
                   "--job-id" job-id
                   "--source-url" (format "http://0.0.0.0:%d/xapi"
                                          (:port source))
                   "--target-url" (format "http://0.0.0.0:%d/xapi"
                                          (:port target))
                   "-p" (format "since=%s" since)
                   "-p" (format "until=%s" until)
                   ;; use the fs
                   "-s" "file"
                   "--file-store-dir" ".test_store"))))
          (testing "retrieves job state"
            (let [{:keys [status message]} (main*
                                            "--job-id" job-id
                                            "--show-job"
                                            "-s" "file"
                                            "--file-store-dir" ".test_store")]
              (is (= 0 status))
              (is (= {:id "foo",
                      :config
                      {:get-buffer-size 10,
                       :statement-buffer-size 500,
                       :batch-buffer-size 10,
                       :batch-timeout 200,
                       :cleanup-buffer-size 50,
                       :source
                       {:request-config
                        {:url-base (format "http://0.0.0.0:%d"
                                           (:port source)), :xapi-prefix "/xapi"},
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
                      {:status :complete,
                       :cursor "2021-10-25T15:05:32.595885000Z",
                       :source {:errors []},
                       :target {:errors []},
                       :errors [],
                       :filter {}}}
                     (update
                      (jj/json->job message)
                      :state dissoc :updated)))))))
      (finally
        (.delete ^File (io/file ".test_store/foo.edn"))
        (.delete ^File (io/file ".test_store"))))))

(deftest reconfigure-test
  (let [common-store (mem/new-store)]
    (with-redefs [cli/create-store (constantly common-store)]
      (sup/with-running [source (sup/lrs
                                 :seed-path
                                 "dev-resources/lrs/after_conf.edn")
                         target-a (sup/lrs)
                         target-b (sup/lrs)
                         target-c (sup/lrs)]
        (testing "CLI runs original job to a"
          (let [;; take a chunk of statements
                ;; there are voideds so the numbers won't match up exactly
                ;; 101 gets us 100
                chunk-a (take 101 (sup/lrs-statements source))

                since-a (-> chunk-a first (get "stored"))

                until-a (-> chunk-a last (get "stored"))
                {:keys [status]}
                (main*
                 "-s" "mem"
                 "--job-id" "reconfigure-test-job"
                 "--source-url" (format "http://0.0.0.0:%d/xapi"
                                        (:port source))
                 "--target-url" (format "http://0.0.0.0:%d/xapi"
                                        (:port target-a))
                 "-p" (format "since=%s" since-a)
                 "-p" (format "until=%s" until-a))]
            (is (= 0 status))
            (is (= 100 (sup/lrs-count target-a)))

            (testing "Reconfigure from CLI args to b"
              (let [since-b until-a
                    chunk-b (->> (sup/lrs-statements source)
                                 (drop 101)
                                 (take 100))
                    until-b (-> chunk-b
                                last
                                (get "stored"))
                    {:keys [status
                            job] :as ret}
                    (main*
                     "-s" "mem"
                     "-f" ;; force resume
                     "--job-id" "reconfigure-test-job"
                     "--target-url" (format "http://0.0.0.0:%d/xapi"
                                            (:port target-b))
                     "-p" (format "since=%s" since-b)
                     "-p" (format "until=%s" until-b))]
                (is (= 0 status))
                (is (= 100 (sup/lrs-count target-b)))

                (testing "Reconfigure from JSON to c"
                  (let [since-c until-b
                        chunk-c (drop 201 (sup/lrs-statements source))
                        until-c (-> chunk-c last (get "stored"))]
                    (is (-> (main*
                             "-s" "mem"
                             "-f" ;; force resume
                             "--job-id" "reconfigure-test-job"
                             "--json" (jj/job->json
                                       (-> job
                                           (update-in
                                            [:config :source :get-params]
                                            merge
                                            {:since since-c
                                             :until until-c})
                                           (assoc-in
                                            [:config :target :request-config :url-base]
                                            (format "http://0.0.0.0:%d"
                                                    (:port target-c))))))
                            :status
                            (= 0)))
                    (is (= 252 (sup/lrs-count target-c)))))))))))))

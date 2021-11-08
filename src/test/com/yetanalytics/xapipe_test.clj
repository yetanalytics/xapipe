(ns com.yetanalytics.xapipe-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe :refer :all]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem]
            [com.yetanalytics.xapipe.test-support :as sup]
            [com.yetanalytics.xapipe.util.time :as t])
  (:import [java.time Instant]))

(use-fixtures :once (sup/instrument-fixture))

(deftest run-job-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe transfers conf test data from source to target"
      (is (= 453 (sup/lrs-count source)))
      (let [[since until] (sup/lrs-stored-range source)
            job-id (.toString (java.util.UUID/randomUUID))
            ;; This test uses a "raw" job from scratch
            job {:id job-id,
                 :config
                 {:source
                  {:request-config
                   {:url-base (format "http://0.0.0.0:%d"
                                      (:port source)),
                    :xapi-prefix "/xapi"},
                   :get-params
                   {:since since
                    :until until}},
                  :target
                  {:request-config
                   {:url-base (format "http://0.0.0.0:%d"
                                      (:port target)),
                    :xapi-prefix "/xapi"}}},
                 :state
                 {:status :init,
                  :cursor "1970-01-01T00:00:00Z",
                  :source {:errors []},
                  :target {:errors []},
                  :errors [],
                  :filter {}}}
            ;; Run the job
            {:keys [stop-fn states]} (run-job job)
            ;; Get all the states
            all-states (a/<!! (a/into [] states))]
        ;; At this point we're done or have errored.
        (let [{{:keys [status
                       cursor]} :state} (last all-states)]
          (when (= status :error)
            (log/error "Job Error" job))
          (testing "successful completion"
            (is (= :complete status)))
          (testing "all statements transferred except empty ref"
            (is (= 452 (sup/lrs-count target))))
          (testing "read up to end"
            (is (= (Instant/parse until) (Instant/parse cursor))))
          (testing "matching statement ids and order"
            (let [source-idset (into #{} (sup/lrs-ids source))]
              (is (every? #(contains? source-idset %)
                          (sup/lrs-ids target))))))))))

;; For brevity, we use a helper to do the job gen for other tests
(defn- init-run-job
  "Given a config, set up and start the job"
  [config]
  (let [;; Generate an ID
        job-id (.toString (java.util.UUID/randomUUID))
        ;; Initialize
        job (job/init-job
             job-id
             config)
        ;; Run the transfer
        {:keys [stop-fn states]} (run-job job)]
    {:job-id job-id
     :job job
     :states states
     :stop-fn stop-fn}))

(deftest run-job-source-error-test
  (sup/with-running [source (sup/lrs)
                     target (sup/lrs)]
    (testing "xapipe bails on source error"
      (let [;; Bad source
            config {:source
                    {:request-config {:url-base    "http://localhost:8123"
                                      :xapi-prefix "/foo"}
                     :get-params     {}
                     :poll-interval  1000
                     :batch-size     50}
                    :target
                    {:request-config (:request-config target)
                     :batch-size     50}}
            {:keys [job-id
                    job
                    stop-fn
                    states]} (init-run-job config)
            all-states (a/<!! (a/into [] states))]
        (is (= [:init :running :error]
               (map
                #(get-in % [:state :status])
                all-states)))
        (is (-> all-states
                last
                :state
                :source
                :errors
                not-empty))))))

(deftest run-job-target-error-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe bails on target error"
      (let [;; Bad source
            config {:source
                    {:request-config (:request-config source)
                     :get-params     {}
                     :poll-interval  1000
                     :batch-size     50}
                    :target
                    {:request-config {:url-base    "http://localhost:8123"
                                      :xapi-prefix "/foo"}
                     :batch-size     50}}
            {:keys [job-id
                    job
                    stop-fn
                    states]} (init-run-job config)
            all-states (a/<!! (a/into [] states))]
        (is (= [:init :running :error]
               (map
                #(get-in % [:state :status])
                all-states)))
        (is (-> all-states
                last
                :state
                :target
                :errors
                not-empty))))))

;; TODO: If you stop a running job, it may post to target several times
;; This can be fixed with more stop-chan checks
(deftest run-job-stop-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe can be stopped by the caller"
      (let [[since until] (sup/lrs-stored-range source)
            config {:source
                    {:request-config (:request-config source)
                     :get-params     {:since since
                                      :until until}}
                    :target
                    {:request-config (:request-config target)}}
            {:keys [job-id
                    job
                    stop-fn
                    states]} (init-run-job config)
            ;; Immediately call the stop-fn!
            _ (stop-fn)
            all-states (a/<!! (a/into [] states))]
        (testing "initializes, runs once but pauses"
          (is (= [:init :running :paused]
                 (mapv #(get-in % [:state :status]) all-states))))
        (testing "nothing gets through"
          (is (= 0 (sup/lrs-count target))))
        (testing "cursor is not moved"
          (is (= (repeat 3 since)
                 (map #(get-in % [:state :cursor]) all-states))))))))

(deftest run-job-backpressure-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe only continues when states are taken"
      (let [[since until] (sup/lrs-stored-range source)
            config {:source
                    {:request-config (:request-config source)
                     :get-params     {:since since
                                      :until until}
                     :batch-size 50}
                    :target
                    {:request-config (:request-config target)
                     :batch-size 50}}
            {:keys [states
                    stop-fn]} (init-run-job config)
            ;; take the first two to get it running
            head-states (a/<!! (a/into [] (a/take 2 states)))
            ;; Wait long enough for one GET + Post
            _ (Thread/sleep 1000)]
        (testing "head states"
          (is (= [:init :running]
                 (map #(get-in % [:state :status])
                      head-states))))
        (testing "one batch gets through"
          (is (= 50 (sup/lrs-count target))))
        (stop-fn)
        ;; Drain remaining states
        (a/<!! (a/into [] states))
        (testing "Nothing else gets through"
          (is (= 50 (sup/lrs-count target))))))))

(deftest store-states-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe stores job state in the given state store"
      (let [store (mem/new-store)
            [since until] (sup/lrs-stored-range source)
            config {:source
                    {:request-config (:request-config source)
                     :get-params     {:since since
                                      :until until}
                     :poll-interval  1000
                     :batch-size     50}
                    :target
                    {:request-config (:request-config target)
                     :batch-size     50}}
            {:keys [job-id
                    job
                    states]} (init-run-job config)]
        (testing "result of store-states is the last state"
          (is (= (assoc job
                        :state {:status :complete
                                :cursor (t/normalize-stamp until)
                                :errors []
                                :source {:errors []}
                                :target {:errors []}
                                :filter {}})
                 (a/<!! (store-states states store))
                 (store/read-job store job-id))))))))

(deftest resume-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe can resume a stopped job"
      (let [store (mem/new-store)
            [since until] (sup/lrs-stored-range source)
            config {:source
                    {:request-config (:request-config source)
                     :get-params     {:since since
                                      :until until}}
                    :target
                    {:request-config (:request-config target)}}
            {:keys [job-id
                    job
                    states
                    stop-fn]} (init-run-job config)
            ;; Immediately stop the job
            _ (stop-fn)
            ;; Drain all states into the store
            last-state (a/<!! (store-states states store))]
        (testing "job is paused"
          (is (= :paused
                 (-> last-state :state :status))))
        (let [;; resume the job
              {:keys [states
                      stop-fn]} (run-job last-state)
              ;; Drain this into the store
              last-state (a/<!! (store-states states store))]
          (testing "job is complete"
            (is (= :complete
                   (-> last-state :state :status)))
            (is (= 452 (sup/lrs-count target)))))))))

;; WIP combined filtering tests with are template
(deftest filter-test
  (sup/art [tag
            target-statements
            other-statements
            config]
           (sup/with-running [source (sup/lrs)
                              target (sup/lrs)]
             (testing (format "testing filter: %s" tag)
               (doseq [s-batch (partition-all 25
                                              (interleave
                                               other-statements
                                               target-statements))]
                 ;; Bump stored time by at least 1 ms for each batch
                 (Thread/sleep 1)
                 ((:load source) s-batch))
               (let [[since until] (sup/lrs-stored-range source)
                     {:keys [job-id
                             job
                             stop-fn
                             states]}
                     (init-run-job
                      (-> config
                          (assoc-in [:source :get-params] {:since since
                                                           :until until})
                          (assoc-in [:source :request-config]
                                    (:request-config source))
                          (assoc-in [:target :request-config]
                                    (:request-config target))))
                     ;; Get all the states
                     all-states (a/<!! (a/into [] states))]
                 (is (-> all-states last :state :status (= :complete)))
                 (is (= (map #(get % "id") target-statements)
                        (map #(get % "id") (sup/lrs-statements target)))))))

           "templates in profile"
           (into []
                 (sup/gen-statements
                  50
                  :profiles
                  ["dev-resources/profiles/calibration_a.jsonld"]
                  :parameters {:seed 42}))
           (into []
                 (concat
                  (sup/gen-statements
                   25
                   :profiles
                   ["dev-resources/profiles/calibration_b.jsonld"]
                   :parameters {:seed 43})
                  (sup/gen-statements
                   25
                   :profiles
                   ["dev-resources/profiles/calibration_c.jsonld"]
                   :parameters {:seed 44})))
           {:filter
            {:template
             {:profile-urls ["dev-resources/profiles/calibration_a.jsonld"]
              :template-ids []}}}

           "patterns in profile"
           (into []
                 (sup/gen-statements
                  50
                  :profiles
                  ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
                  :parameters {:seed 42}))
           (into []
                 (concat
                  (sup/gen-statements
                   25
                   :profiles
                   ["dev-resources/profiles/calibration_a.jsonld"]
                   :parameters {:seed 43})
                  (sup/gen-statements
                   25
                   :profiles
                   ["dev-resources/profiles/calibration_b.jsonld"]
                   :parameters {:seed 44})))
           {:filter
            {:pattern
             {:profile-urls ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
              :pattern-ids []}}}

           "templates and patterns in profile (redundant but possible)"
           (into []
                 (sup/gen-statements
                  50
                  :profiles
                  ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
                  :parameters {:seed 42}))
           (into []
                 (concat
                  (sup/gen-statements
                   25
                   :profiles
                   ["dev-resources/profiles/calibration_a.jsonld"]
                   :parameters {:seed 43})
                  (sup/gen-statements
                   25
                   :profiles
                   ["dev-resources/profiles/calibration_b.jsonld"]
                   :parameters {:seed 44})))
           {:filter
            {:template
             {:profile-urls ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
              :template-ids []}
             :pattern
             {:profile-urls ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
              :pattern-ids []}}}))

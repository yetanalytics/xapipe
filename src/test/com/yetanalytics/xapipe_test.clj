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

(deftest run-job-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe transfers conf test data from source to target"
      ;; Make sure it's in there
      (is (= 453 (sup/lrs-count source)))
      (let [[since until] (sup/lrs-stored-range source)
            config {:source
                    {:request-config (:request-config source)
                     :get-params     {:since since
                                      :until until}
                     :poll-interval  1000
                     :batch-size     50}
                    :target
                    {:request-config (:request-config target)
                     :batch-size     50}}
            ;; Generate an ID
            job-id (.toString (java.util.UUID/randomUUID))
            ;; Initialize
            job (job/init-job
                 job-id
                 config)
            ;; Run the transfer
            {:keys [stop-fn states]} (run-job job)
            ;; Get all the states
            all-states (a/<!! (a/go-loop [acc []]
                                (if-let [state (a/<! states)]
                                  (do
                                    (log/debug "state" state)
                                    (recur (conj acc state)))
                                  acc)))]
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
            job-id (.toString (java.util.UUID/randomUUID))
            job (job/init-job
                 job-id
                 config)
            {:keys [states]} (run-job job)
            all-states (a/<!! (a/go-loop [acc []]
                                (if-let [state (a/<! states)]
                                  (do
                                    (recur (conj acc state)))
                                  acc)))]
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
            job-id (.toString (java.util.UUID/randomUUID))
            job (job/init-job
                 job-id
                 config)
            {:keys [states]} (run-job job)
            all-states (a/<!! (a/go-loop [acc []]
                                (if-let [state (a/<! states)]
                                  (do
                                    (recur (conj acc state)))
                                  acc)))]
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
            job-id (.toString (java.util.UUID/randomUUID))
            job (job/init-job
                 job-id
                 config)
            {:keys [states]} (run-job job)]
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
                     ;; Generate an ID
                     job-id (.toString (java.util.UUID/randomUUID))
                     ;; Initialize
                     job (job/init-job
                          job-id
                          (-> config
                              (assoc-in [:source :get-params] {:since since
                                                               :until until})
                              (assoc-in [:source :request-config]
                                        (:request-config source))
                              (assoc-in [:target :request-config]
                                        (:request-config target))))
                     ;; Run the transfer
                     {:keys [stop-fn states]} (run-job job)
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

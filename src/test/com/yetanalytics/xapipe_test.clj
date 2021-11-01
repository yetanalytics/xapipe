(ns com.yetanalytics.xapipe-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe :refer :all]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem]
            [com.yetanalytics.xapipe.test-support :as support]
            [com.yetanalytics.xapipe.util.time :as t])
  (:import [java.time Instant]))

(use-fixtures :each (partial support/source-target-fixture
                             {:seed-path "dev-resources/lrs/after_conf.edn"}))

(deftest run-job-test
  (testing "xapipe transfers conf test data from source to target"
    ;; Make sure it's in there
    (is (= 453 (support/lrs-count support/*source-lrs*)))
    (let [[since until] (support/lrs-stored-range support/*source-lrs*)
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
          (is (= 452 (support/lrs-count support/*target-lrs*))))
        (testing "read up to end"
          (is (= (Instant/parse until) (Instant/parse cursor))))
        (testing "matching statement ids and order"
          (let [source-idset (into #{} (support/lrs-ids support/*source-lrs*))]
            (is (every? #(contains? source-idset %)
                        (support/lrs-ids support/*target-lrs*)))))))))

(deftest run-job-source-error-test
  (testing "xapipe bails on source error"
    (let [;; Bad source
          config {:source
                  {:request-config {:url-base    "http://localhost:8123"
                                    :xapi-prefix "/foo"}
                   :get-params     {}
                   :poll-interval  1000
                   :batch-size     50}
                  :target
                  {:request-config (:request-config support/*target-lrs*)
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
              not-empty)))))

(deftest run-job-target-error-test
  (testing "xapipe bails on target error"
    (let [;; Bad source
          config {:source
                  {:request-config (:request-config support/*source-lrs*)
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
              not-empty)))))

(deftest store-states-test
  (testing "xapipe stores job state in the given state store"
    (let [store (mem/new-store)
          [since until] (support/lrs-stored-range support/*source-lrs*)
          config {:source
                  {:request-config (:request-config support/*source-lrs*)
                   :get-params     {:since since
                                    :until until}
                   :poll-interval  1000
                   :batch-size     50}
                  :target
                  {:request-config (:request-config support/*target-lrs*)
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
               (store/read-job store job-id)))))))

(deftest filter-template-test
  (testing "xapipe filters based on profile statement templates"
    ;; To start, the fixture has placed the conf test statements
    (is (= 453 (support/lrs-count support/*source-lrs*)))
    ;; We generate 50 statements from a profile.
    (let [calibration-statements (support/gen-statements
                                  50 :profiles
                                  ["dev-resources/profiles/calibration.jsonld"])]
      ;; And load them
      ((:load support/*source-lrs*) calibration-statements)
      ;; for a total of 503:
      (is (= 503 (support/lrs-count support/*source-lrs*)))
      ;; Set up and run the transfer
      (let [[since until] (support/lrs-stored-range support/*source-lrs*)
            config {:filter
                    {:template
                     {:profile-urls ["dev-resources/profiles/calibration.jsonld"]
                      :template-ids []}}
                    :source
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
            ;; Run the transfer
            {:keys [stop-fn states]} (run-job job)
            ;; Get all the states
            all-states (a/<!! (a/go-loop [acc []]
                                (if-let [state (a/<! states)]
                                  (do
                                    (log/debug "state" state)
                                    (recur (conj acc state)))
                                  acc)))]
        (let []
          ;; At this point we're done or have errored.
          (let [{{:keys [status
                         cursor]} :state} (last all-states)]
            (testing "successful completion"
              (is (= :complete status)))
            (testing "only calibration statements transferred"
              (is (= 50 (support/lrs-count support/*target-lrs*)))
              (is (= (map #(get % "id") calibration-statements)
                     (map #(get % "id") (support/lrs-statements support/*target-lrs*)))))))))))

(deftest filter-pattern-test
  (testing "xapipe filters based on profile patterns"
    ;; To start, the fixture has placed the conf test statements, none of which
    ;; should be included in result
    (is (= 453 (support/lrs-count support/*source-lrs*)))
    ;; We generate 3 statements from a profile with a strict pattern
    (let [profile-url "dev-resources/profiles/calibration_strict_pattern.jsonld"
          calibration-statements (support/gen-statements
                                  3
                                  :profiles [profile-url]
                                  :personae [{:name "Test Subjects",
                                              :objectType "Group",
                                              :member
                                              [{:name "alice",
                                                :mbox "mailto:alice@example.org",
                                                :objectType "Agent"}]}])]
      ;; And load them
      ((:load support/*source-lrs*) calibration-statements)
      ;; for a total of 456:
      (is (= 456 (support/lrs-count support/*source-lrs*)))
      ;; Set up and run the transfer
      (let [[since until] (support/lrs-stored-range support/*source-lrs*)
            config {:filter
                    {:pattern
                     {:profile-urls [profile-url]
                      :pattern-ids []}}
                    :source
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
            ;; Run the transfer
            {:keys [stop-fn states]} (run-job job)
            ;; Get all the states
            all-states (a/<!! (a/go-loop [acc []]
                                (if-let [state (a/<! states)]
                                  (do
                                    (log/debug "state" state)
                                    (recur (conj acc state)))
                                  acc)))]
        (let []
          ;; At this point we're done or have errored.
          (let [{{:keys [status
                         cursor]} :state} (last all-states)]
            (testing "successful completion"
              (is (= :complete status)))
            (testing "only calibration statements transferred"
              (is (= 3 (support/lrs-count support/*target-lrs*)))
              (is (= (map #(get % "id") calibration-statements)
                     (map #(get % "id") (support/lrs-statements support/*target-lrs*)))))))))))

(deftest all-filter-test
  (testing "xapipe filters based on profile templates + patterns"
    ;; To start, the fixture has placed the conf test statements, none of which
    ;; should be included in result
    (is (= 453 (support/lrs-count support/*source-lrs*)))
    ;; We generate 3 statements from a profile with a strict pattern
    (let [profile-url "dev-resources/profiles/calibration_strict_pattern.jsonld"
          calibration-statements (support/gen-statements
                                  3
                                  :profiles [profile-url]
                                  :personae [{:name "Test Subjects",
                                              :objectType "Group",
                                              :member
                                              [{:name "alice",
                                                :mbox "mailto:alice@example.org",
                                                :objectType "Agent"}]}])]
      ;; And load them
      ((:load support/*source-lrs*) calibration-statements)
      ;; for a total of 456:
      (is (= 456 (support/lrs-count support/*source-lrs*)))
      ;; Set up and run the transfer
      (let [[since until] (support/lrs-stored-range support/*source-lrs*)
            config {:filter
                    {;; In this case the filters are redundant
                     :template
                     {:profile-urls [profile-url]
                      :pattern-ids []}
                     :pattern
                     {:profile-urls [profile-url]
                      :pattern-ids []}}
                    :source
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
            ;; Run the transfer
            {:keys [stop-fn states]} (run-job job)
            ;; Get all the states
            all-states (a/<!! (a/go-loop [acc []]
                                (if-let [state (a/<! states)]
                                  (do
                                    (log/debug "state" state)
                                    (recur (conj acc state)))
                                  acc)))]
        (let []
          ;; At this point we're done or have errored.
          (let [{{:keys [status
                         cursor]} :state} (last all-states)]
            (testing "successful completion"
              (is (= :complete status)))
            (testing "only calibration statements transferred"
              (is (= 3 (support/lrs-count support/*target-lrs*)))
              (is (= (map #(get % "id") calibration-statements)
                     (map #(get % "id") (support/lrs-statements support/*target-lrs*)))))))))))

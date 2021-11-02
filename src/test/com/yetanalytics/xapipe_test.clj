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

;; TODO: filter-test works but filter-test-2 doesn't. Why?

#_(deftest filter-test
    (are [n
          gen-profile
          config]
        (sup/with-running [source (sup/lrs
                                   :seed-path "dev-resources/lrs/after_conf.edn")
                           target (sup/lrs)]
          (let [calibration-statements (sup/gen-statements
                                        n :profiles
                                        [gen-profile])]
            ((:load source) calibration-statements)
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
                  all-states (a/<!! (a/go-loop [acc []]
                                      (if-let [state (a/<! states)]
                                        (do
                                          (log/debug "state" state)
                                          (recur (conj acc state)))
                                        acc)))]
              (and (-> all-states last :state :status (= :complete))
                   (= n (sup/lrs-count target))
                   (= (map #(get % "id") calibration-statements)
                      (map #(get % "id") (sup/lrs-statements target)))))))
      50
      "dev-resources/profiles/calibration.jsonld"
      {:filter
       {:template
        {:profile-urls ["dev-resources/profiles/calibration.jsonld"]
         :template-ids []}}}

      ))


#_(deftest filter-test-2
    (are [target-statements
          other-statements
          config]
        (sup/with-running [source (sup/lrs)
                           target (sup/lrs)]
          (do
            (doseq [s-batch (partition-all 50
                                           (interleave target-statements
                                                       other-statements))]
              ((:load source) s-batch))
            (Thread/sleep 1000)
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
                  _ (println job)
                  ;; Run the transfer
                  {:keys [stop-fn states]} (run-job job)
                  ;; Get all the states
                  all-states (a/<!! (a/into [] states))]
              (println (sup/lrs-count target))
              (and (-> all-states last :state :status (= :complete))
                   (= (map #(get % "id") target-statements)
                      (map #(get % "id") (sup/lrs-statements target)))))))
      (into []
            (sup/gen-statements
             50
             :profiles
             ["dev-resources/profiles/calibration.jsonld"]
             :parameters {:seed 42}))
      (into []
            (sup/gen-statements
             50
             :profiles
             ["dev-resources/profiles/tccc.jsonld"]
             :parameters {:seed 24}))
      {:filter
       {:template
        {:profile-urls ["dev-resources/profiles/calibration.jsonld"]
         :template-ids []}}
       :source
       {:poll-interval  1000
        :batch-size     50}
       :target
       {:batch-size     50}}

      ))


(deftest filter-template-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe filters based on profile statement templates"
      ;; To start, the fixture has placed the conf test statements
      (is (= 453 (sup/lrs-count source)))
      ;; We generate 50 statements from a profile.
      (let [calibration-statements (sup/gen-statements
                                    50 :profiles
                                    ["dev-resources/profiles/calibration.jsonld"])]
        ;; And load them
        ((:load source) calibration-statements)
        ;; for a total of 503:
        (is (= 503 (sup/lrs-count source)))
        ;; Set up and run the transfer
        (let [[since until] (sup/lrs-stored-range source)
              config {:filter
                      {:template
                       {:profile-urls ["dev-resources/profiles/calibration.jsonld"]
                        :template-ids []}}
                      :source
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
          (let []
            ;; At this point we're done or have errored.
            (let [{{:keys [status
                           cursor]} :state} (last all-states)]
              (testing "successful completion"
                (is (= :complete status)))
              (testing "only calibration statements transferred"
                (is (= 50 (sup/lrs-count target)))
                (is (= (map #(get % "id") calibration-statements)
                       (map #(get % "id") (sup/lrs-statements target))))))))))))

(deftest filter-pattern-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe filters based on profile patterns"
      ;; To start, the fixture has placed the conf test statements, none of which
      ;; should be included in result
      (is (= 453 (sup/lrs-count source)))
      ;; We generate 3 statements from a profile with a strict pattern
      (let [profile-url "dev-resources/profiles/calibration_strict_pattern.jsonld"
            calibration-statements (sup/gen-statements
                                    3
                                    :profiles [profile-url]
                                    :personae [{:name "Test Subjects",
                                                :objectType "Group",
                                                :member
                                                [{:name "alice",
                                                  :mbox "mailto:alice@example.org",
                                                  :objectType "Agent"}]}])]
        ;; And load them
        ((:load source) calibration-statements)
        ;; for a total of 456:
        (is (= 456 (sup/lrs-count source)))
        ;; Set up and run the transfer
        (let [[since until] (sup/lrs-stored-range source)
              config {:filter
                      {:pattern
                       {:profile-urls [profile-url]
                        :pattern-ids []}}
                      :source
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
          (let []
            ;; At this point we're done or have errored.
            (let [{{:keys [status
                           cursor]
                    last-filter-state :filter} :state} (last all-states)]
              (testing "successful completion"
                (is (= :complete status)))
              (testing "only calibration statements transferred"
                (is (= 3 (sup/lrs-count target)))
                (is (= (map #(get % "id") calibration-statements)
                       (map #(get % "id") (sup/lrs-statements target)))))
              (testing "outputs state"
                (testing "last is empty because reset"
                  (is (= {:pattern {}}
                         last-filter-state)))))))))))

(deftest all-filter-test
  (sup/with-running [source (sup/lrs
                             :seed-path "dev-resources/lrs/after_conf.edn")
                     target (sup/lrs)]
    (testing "xapipe filters based on profile templates + patterns"
      ;; To start, the fixture has placed the conf test statements, none of which
      ;; should be included in result
      (is (= 453 (sup/lrs-count source)))
      ;; We generate 3 statements from a profile with a strict pattern
      (let [profile-url "dev-resources/profiles/calibration_strict_pattern.jsonld"
            calibration-statements (sup/gen-statements
                                    3
                                    :profiles [profile-url]
                                    :personae [{:name "Test Subjects",
                                                :objectType "Group",
                                                :member
                                                [{:name "alice",
                                                  :mbox "mailto:alice@example.org",
                                                  :objectType "Agent"}]}])]
        ;; And load them
        ((:load source) calibration-statements)
        ;; for a total of 456:
        (is (= 456 (sup/lrs-count source)))
        ;; Set up and run the transfer
        (let [[since until] (sup/lrs-stored-range source)
              config {:filter
                      {;; In this case the filters are redundant
                       :template
                       {:profile-urls [profile-url]
                        :pattern-ids []}
                       :pattern
                       {:profile-urls [profile-url]
                        :pattern-ids []}}
                      :source
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
          (let []
            ;; At this point we're done or have errored.
            (let [{{:keys [status
                           cursor]} :state} (last all-states)]
              (testing "successful completion"
                (is (= :complete status)))
              (testing "only calibration statements transferred"
                (is (= 3 (sup/lrs-count target)))
                (is (= (map #(get % "id") calibration-statements)
                       (map #(get % "id") (sup/lrs-statements target))))))))))))

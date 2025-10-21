(ns com.yetanalytics.xapipe.cli-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.cli :refer :all]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.test-support :as sup]))

(use-fixtures :once (sup/instrument-fixture))

(deftest create-store-test
  (is (satisfies? store/XapipeStore (create-store {:storage :noop})))
  (is (satisfies? store/XapipeStore (create-store {:storage :redis
                                                   :redis-uri "redis://0.0.0.0:6379"
                                                   :refis-prefix "whatever"})))
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
  (testing "minimal"
    (is (= {:source
            {:request-config
             {:url-base "http://0.0.0.0:8080",
              :xapi-prefix "/xapi"}},
            :target
            {:request-config
             {:url-base "http://0.0.0.0:8081",
              :xapi-prefix "/xapi"}},
            :filter {}}
           (options->config
            {:source-url "http://0.0.0.0:8080/xapi"
             :target-url "http://0.0.0.0:8081/xapi"}))))
  (testing "complex"
    (is (= {:get-buffer-size 100,
            :batch-timeout 200,
            :cleanup-buffer-size 100
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
             :cleanup-buffer-size 100

             :filter-template-profile-urls []
             :filter-template-ids []
             :filter-pattern-profile-urls []
             :filter-pattern-ids []

             :statement-buffer-size 1000
             :batch-buffer-size 100})))))

(deftest create-job-test
  (is (= {:id "foo",
          :version 1,
          :config
          {:get-buffer-size 100,
           :statement-buffer-size 1000,
           :batch-buffer-size 100,
           :batch-timeout 200,
           :cleanup-buffer-size 50,
           :source
           {:request-config
            {:url-base "http://0.0.0.0:8080",
             :xapi-prefix "/xapi",
             :xapi-version "1.0.3",
             :username "foo",
             :password "bar",
             :oauth-params {:auth-uri "http://example.com/token"
                            :client-id "foo"
                            :client-secret "bar"}},
            :get-params {:limit 50},
            :poll-interval 1000,
            :batch-size 50,
            :backoff-opts
            {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
           :target
           {:request-config
            {:url-base "http://0.0.0.0:8081",
             :xapi-prefix "/xapi",
             :xapi-version "1.0.3",
             :username "foo",
             :password "bar",
             :oauth-params {:auth-uri "http://example.com/token"
                            :client-id "foo"
                            :client-secret "bar"}},
            :batch-size 50,
            :backoff-opts
            {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
           :filter {}},
          :state
          {:status :init,
           :cursor "1970-01-01T00:00:00.000000000Z",
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
           :source-auth-uri "http://example.com/token"
           :source-client-id "foo"
           :source-client-secret "bar"
           :source-backoff-budget 1000
           :source-backoff-max-attempt 10
           :source-backoff-j-range 10
           :source-backoff-initial 1
           :source-xapi-version "1.0.3"

           :target-url "http://0.0.0.0:8081/xapi"
           :target-batch-size 50
           :target-username "foo"
           :target-password "bar"
           :target-auth-uri "http://example.com/token"
           :target-client-id "foo"
           :target-client-secret "bar"
           :target-backoff-budget 1000
           :target-backoff-max-attempt 10
           :target-backoff-j-range 10
           :target-backoff-initial 1
           :target-xapi-version "1.0.3"

           :get-buffer-size 100
           :batch-timeout 200
           :cleanup-buffer-size 50

           :filter-template-profile-urls []
           :filter-template-ids []
           :filter-pattern-profile-urls []
           :filter-pattern-ids []

           :statement-buffer-size 1000
           :batch-buffer-size 100}))))

(deftest only-auth-test
  (let [config {:get-buffer-size 100,
                :batch-timeout 200,
                :cleanup-buffer-size 100
                :source
                {:request-config
                 {:url-base "http://0.0.0.0:8080",
                  :xapi-prefix "/xapi",
                  :xapi-version "1.0.3"},
                 :get-params {},
                 :poll-interval 1000,
                 :batch-size 50,
                 :backoff-opts
                 {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
                :target
                {:request-config
                 {:url-base "http://0.0.0.0:8081",
                  :xapi-prefix "/xapi",
                  :xapi-version "1.0.3"},
                 :batch-size 50,
                 :backoff-opts
                 {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
                :filter {},
                :statement-buffer-size 1000,
                :batch-buffer-size 100}
        ambig-config (-> config
                         (update-in [:source :request-config]
                                    merge
                                    {:username "foo"
                                     :password "bar"
                                     :token "foobar"
                                     :oauth-params
                                     {:auth-uri "http://example.com/token"
                                      :client-id "foo"
                                      :client-secret "bar"}}))]
    (are [auth-type req-config-after]
        (-> (only-auth ambig-config :source auth-type)
            (get-in [:source :request-config])
            (select-keys [:username
                          :password
                          :token
                          :oauth-params]))
      :basic {:username "foo"
              :password "bar"}
      :token {:token "foobar"}
      :oauth {:oauth-params
              {:auth-uri "http://example.com/token"
               :client-id "foo"
               :client-secret "bar"}})))

(deftest reconfigure-with-options-test
  (let [config {:get-buffer-size 100,
                :statement-buffer-size 1000,
                :batch-buffer-size 100,
                :batch-timeout 200,
                :cleanup-buffer-size 50,
                :source
                {:request-config
                 {:url-base "http://0.0.0.0:8080",
                  :xapi-prefix "/xapi",
                  :xapi-version "1.0.3",
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
                  :xapi-version "1.0.3",
                  :username "foo",
                  :password "bar"},
                 :batch-size 50,
                 :backoff-opts
                 {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
                :filter {}}]
    (testing "General reconfig"
      (is (= {:get-buffer-size 200,
              :batch-timeout 300,
              :statement-buffer-size 10000,
              :batch-buffer-size 1000,
              :cleanup-buffer-size 100,
              :source
              {:request-config
               {:url-base "http://0.0.0.0:8082",
                :xapi-prefix "/xapi2",
                :xapi-version "1.0.3",
                :username "baz",
                :password "quxx"},
               :get-params {:format "exact"
                            :limit 100},
               :poll-interval 3000,
               :batch-size 100,
               :backoff-opts
               {:budget 999, :max-attempt 9, :j-range 9, :initial 2}},
              :target
              {:request-config
               {:url-base "http://0.0.0.0:8083",
                :xapi-prefix "/xapi2",
                :xapi-version "1.0.3",
                :username "baz",
                :password "quxx"},
               :batch-size 100,
               :backoff-opts
               {:budget 999, :max-attempt 9, :j-range 9, :initial 2}},
              :filter {}}
             (reconfigure-with-options
              config
              {:job-id "foo"
               :source-url "http://0.0.0.0:8082/xapi2"
               :source-batch-size 100
               :source-poll-interval 3000
               :get-params {:format "exact"}
               :source-username "baz"
               :source-password "quxx"
               :source-backoff-budget 999
               :source-backoff-max-attempt 9
               :source-backoff-j-range 9
               :source-backoff-initial 2

               :target-url "http://0.0.0.0:8083/xapi2"
               :target-batch-size 100
               :target-username "baz"
               :target-password "quxx"
               :target-backoff-budget 999
               :target-backoff-max-attempt 9
               :target-backoff-j-range 9
               :target-backoff-initial 2

               :get-buffer-size 200
               :batch-timeout 300
               :cleanup-buffer-size 100

               :statement-buffer-size 10000
               :batch-buffer-size 1000}))))
    (testing "Auth change"
      (is (= {:url-base "http://0.0.0.0:8080",
              :xapi-prefix "/xapi",
              :xapi-version "1.0.3",
              :token "foobar"}
             (-> (reconfigure-with-options
                  config
                  {:source-token "foobar"})
                 (get-in [:source :request-config])))))))

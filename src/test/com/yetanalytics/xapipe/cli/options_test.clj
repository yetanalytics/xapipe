(ns com.yetanalytics.xapipe.cli.options-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [clojure.tools.cli :as cli]
            [com.yetanalytics.xapipe.cli.options :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]))

(use-fixtures :once (sup/instrument-fixture))

(deftest common-options-test
  (sup/art [args options errors]
           (is (= {:options options
                   :errors errors}
                  (select-keys
                   (cli/parse-opts
                    args common-options)
                   [:options :errors])))
           ;; Default
           []
           {:help false,
            :show-job false,
            :list-jobs false,
            :force-resume false,
            :storage :file,
            :redis-uri "redis://0.0.0.0:6379",
            :redis-prefix "xapipe"
            :file-store-dir "store"
            :metrics-reporter "noop"
            :prometheus-push-gateway "0.0.0.0:9091"}
           nil

           ;; Redis store with a custom uri
           ["-s" "redis" "--redis-uri" "redis://localhost:1234"]
           {:help false,
            :show-job false,
            :list-jobs false,
            :force-resume false,
            :storage :redis,
            :redis-uri "redis://localhost:1234",
            :redis-prefix "xapipe"
            :file-store-dir "store"
            :metrics-reporter "noop"
            :prometheus-push-gateway "0.0.0.0:9091"}
           nil))

(deftest source-options-test
  (sup/art [args options errors]
           (is (= {:options options
                   :errors errors}
                  (select-keys
                   (cli/parse-opts
                    args source-options)
                   [:options :errors])))
           ;; Default
           []
           {:source-batch-size 50,
            :source-poll-interval 1000,
            :get-params {},
            :source-backoff-budget 10000,
            :source-backoff-max-attempt 10}
           nil

           ;; invalid batch size
           ["--source-batch-size" "0"]
           {:source-batch-size 50,
            :source-poll-interval 1000,
            :get-params {},
            :source-backoff-budget 10000,
            :source-backoff-max-attempt 10}
           ["Failed to validate \"--source-batch-size 0\": Must be a positive integer"]

           ;; xAPI Params
           ["-p" "since=2021-10-26T17:51:06.530464Z"
            "-p" "related_agents=true"
            ;; invalid params will be filtered out
            "-p" "attachments=false"
            "-p" "(*^(*%))"]
           {:source-batch-size 50,
            :source-poll-interval 1000,
            :get-params {:since "2021-10-26T17:51:06.530464Z"
                         :related_agents true},
            :source-backoff-budget 10000,
            :source-backoff-max-attempt 10}
           nil))

(deftest target-options-test
  (is (= {:options {:target-batch-size 50
                    :target-backoff-budget 10000
                    :target-backoff-max-attempt 10}
          :errors nil}
         (select-keys
          (cli/parse-opts [] target-options)
          [:options :errors]))))

(deftest job-options-test
  (is (= {:options
          {:get-buffer-size 10,
           :batch-timeout 200,
           :filter-template-profile-urls [],
           :filter-template-ids [],
           :filter-pattern-profile-urls [],
           :filter-pattern-ids []},
          :errors nil}
         (select-keys
          (cli/parse-opts [] job-options)
          [:options :errors]))))

(def default-options
  "options w/no args"
  {:filter-pattern-profile-urls [],
   :filter-template-profile-urls [],
   :source-backoff-max-attempt 10,
   :target-backoff-max-attempt 10,
   :source-backoff-budget 10000,
   :file-store-dir "store",
   :get-params {},
   :filter-template-ids [],
   :redis-uri "redis://0.0.0.0:6379",
   :batch-timeout 200,
   :source-poll-interval 1000,
   :source-batch-size 50,
   :target-backoff-budget 10000,
   :force-resume false,
   :redis-prefix "xapipe",
   :storage :file,
   :get-buffer-size 10,
   :target-batch-size 50,
   :show-job false,
   :help false,
   :list-jobs false,
   :filter-pattern-ids [],
   :metrics-reporter "noop",
   :prometheus-push-gateway "0.0.0.0:9091"})

;; Matches the fixture job we have
(def json-job
  {:id "foo",
   :config
   {:get-buffer-size 10,
    :statement-buffer-size 500,
    :batch-buffer-size 10,
    :batch-timeout 200,
    :source
    {:request-config
     {:url-base "http://0.0.0.0:8080", :xapi-prefix "/xapi"},
     :batch-size 50,
     :backoff-opts {:budget 10000, :max-attempt 10},
     :poll-interval 1000,
     :get-params {:limit 50}},
    :target
    {:request-config
     {:url-base "http://0.0.0.0:8081", :xapi-prefix "/xapi"},
     :batch-size 50,
     :backoff-opts {:budget 10000, :max-attempt 10}},
    :filter {}},
   :state
   {:status :init,
    :cursor "1970-01-01T00:00:00.000000000Z",
    :source {:errors []},
    :target {:errors []},
    :errors [],
    :filter {}}})

(deftest args->options-test
  (sup/art
   [args options errors]
   (let [{opts :options
          errs :errors} (args->options args)]
     (testing "assert options and errors"
       (is (= options
              opts))
       (is (= (not-empty errors)
              (not-empty errs)))))
   ;; Assert Default opts
   [] default-options []

   ;; Assert all opts
   ["--help"
    "--job-id" "foo"
    "--conn-timeout" "1"
    "--conn-threads" "1"
    "--conn-default-per-route" "1"
    "--conn-insecure?"
    "--conn-io-thread-count" "1"
    "--show-job"
    "--list-jobs"
    "--delete-job" "foo"
    "--force-resume"
    "--json" (slurp "dev-resources/jobs/simple.json")
    "--json-file" "dev-resources/jobs/simple.json"
    "--storage" "file"
    "--redis-uri" "redis://0.0.0.0:1111"
    "--redis-prefix" "my-xapipe"
    "--file-store-dir" "somedir"
    "--metrics-reporter" "prometheus"
    "--prometheus-push-gateway" "localhost:1234"
    "--source-url" "http://0.0.0.0:8080/xapi"
    "--source-batch-size" "1"
    "--source-poll-interval" "1"
    "--xapi-get-param" "format=exact"
    "--source-username" "foo"
    "--source-password" "bar"
    "--source-backoff-budget" "1"
    "--source-backoff-max-attempt" "1"
    "--source-backoff-j-range" "1"
    "--source-backoff-initial" "1"
    "--target-url" "http://0.0.0.0:8081/xapi"
    "--target-batch-size" "1"
    "--target-username" "foo"
    "--target-password" "bar"
    "--target-backoff-budget" "1"
    "--target-backoff-max-attempt" "1"
    "--target-backoff-j-range" "1"
    "--target-backoff-initial" "1"
    "--get-buffer-size" "1"
    "--batch-timeout" "1"
    "--template-profile-url" "http://example.org/profile.jsonld"
    "--template-id" "http://example.org/profile.jsonld#foo"
    "--pattern-profile-url" "http://example.org/profile.jsonld"
    "--pattern-id" "http://example.org/profile.jsonld#foo"
    "--statement-buffer-size" "1"
    "--batch-buffer-size" "1"
    ]
   {:source-password "bar",
    :filter-pattern-profile-urls ["http://example.org/profile.jsonld"],
    :delete-job "foo",
    :filter-template-profile-urls ["http://example.org/profile.jsonld"],
    :source-backoff-max-attempt 1,
    :target-backoff-max-attempt 1,
    :source-username "foo",
    :json-file json-job,
    :job-id "foo",
    :conn-timeout 1,
    :statement-buffer-size 1,
    :source-backoff-budget 1,
    :file-store-dir "somedir",
    :batch-buffer-size 1,
    :conn-io-thread-count 1,
    :get-params {:format "exact"},
    :conn-threads 1,
    :filter-template-ids ["http://example.org/profile.jsonld#foo"],
    :redis-uri "redis://0.0.0.0:1111",
    :source-url "http://0.0.0.0:8080/xapi",
    :batch-timeout 1,
    :source-poll-interval 1,
    :source-batch-size 1,
    :target-backoff-budget 1,
    :conn-insecure? true,
    :target-username "foo",
    :target-backoff-j-range 1,
    :force-resume true,
    :redis-prefix "my-xapipe",
    :source-backoff-j-range 1,
    :storage :file,
    :get-buffer-size 1,
    :target-backoff-initial 1,
    :target-password "bar",
    :target-batch-size 1,
    :show-job true,
    :help true,
    :json json-job,
    :list-jobs true,
    :source-backoff-initial 1,
    :filter-pattern-ids ["http://example.org/profile.jsonld#foo"],
    :conn-default-per-route 1,
    :target-url "http://0.0.0.0:8081/xapi"
    :metrics-reporter "prometheus"
    :prometheus-push-gateway "localhost:1234"}
   []))

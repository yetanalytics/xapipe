(ns com.yetanalytics.xapipe.cli.options-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.cli.options :refer :all]
            [clojure.tools.cli :as cli]))

(deftest common-options-test
  (are [args options errors]
      (= {:options options
          :errors errors}
         (select-keys
          (cli/parse-opts
           args common-options)
          [:options :errors]))
    ;; Default
    []
    {:help false,
     :storage :noop,
     :redis-host "0.0.0.0",
     :redis-port 6379}
    nil

    ;; Redis store with a custom port
    ["-s" "redis" "--redis-port" "1234"]
    {:help false,
     :storage :redis,
     :redis-host "0.0.0.0",
     :redis-port 1234}
    nil))

(deftest source-options-test
  (are [args options errors]
      (= {:options options
          :errors errors}
         (select-keys
          (cli/parse-opts
           args source-options)
          [:options :errors]))
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
           :filter-pattern-ids [],
           :show-job false},
          :errors nil}
         (select-keys
          (cli/parse-opts [] job-options)
          [:options :errors]))))

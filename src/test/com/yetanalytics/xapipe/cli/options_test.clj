(ns com.yetanalytics.xapipe.cli.options-test
  (:require [clojure.test :refer :all]
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
            :storage :noop,
            :redis-uri "redis://0.0.0.0:6379",
            :redis-prefix "xapipe"
            :file-store-dir "store"}
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
            :file-store-dir "store"}
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
   :storage :noop,
   :get-buffer-size 10,
   :target-batch-size 50,
   :show-job false,
   :help false,
   :list-jobs false,
   :filter-pattern-ids []})

(deftest args->options-test
  (sup/art
   [args options errors]
   (let [{opts :options
          errs :errors} (args->options args)]
     (testing "assert options and errors"
       (is (= options
              options))
       (is (= (not-empty errors)
              (not-empty errs)))))

   [] default-options []))

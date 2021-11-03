(ns com.yetanalytics.xapipe.cli.options
  "clojure.tools.cli options for xapipe CLI"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.cli :as cli]
            [com.yetanalytics.xapipe.job.config :as config]))

(def storage-options
  [["-s" "--storage STORAGE" "Select storage backend, noop (default) or redis, mem is for testing only"
    :default :noop
    :parse-fn keyword
    :validate [#{:noop
                 :redis
                 :mem} "Must be: noop | redis | mem"]]
   ;; Redis Backend Options
   ;; TODO: auth, or just let them pass the redis url
   [nil "--redis-host HOST" "Redis Host"
    :default "0.0.0.0"]
   [nil "--redis-port PORT" "Redis Port"
    :default 6379
    :parse-fn #(Long/parseLong %)]
   [nil "--redis-prefix" "Redis key prefix"
    :default "xapipe"]])

(defn- keywordize-status
  [job]
  (update-in job [:state :status] (partial keyword nil)))

(def common-options
  (into
   [["-h" "--help" "Show the help and options summary"
     :default false]
    [nil "--job-id ID" "Job ID"]
    ;; Connection Manager Options
    [nil "--conn-timeout TIMEOUT" "Connection Manager Connection Timeout"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--conn-threads THREADS" "Connection Manager Max Threads"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--conn-default-per-route CONNS" "Connection Manager Simultaneous Connections Per Host"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--conn-insecure?" "Allow Insecure HTTPS Connections"]
    [nil "--conn-io-thread-count THREADS" "Connection Manager I/O Thread Pool Size, default is number of processors"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--show-job" "Show the job and exit"
     :default false]
    [nil "--list-jobs" "List jobs in persistent storage"
     :default false]
    ["-f" "--force-resume" "If resuming a job, clear any errors and force it to resume."
     :default false]
    [nil "--json JSON" "Take a job specification as a JSON string"
     :parse-fn #(-> (json/parse-string ^String % (partial keyword nil))
                    keywordize-status
                    (update :config config/ensure-defaults))]
    [nil "--json-file FILE" "Take a job specification from a JSON file"
     :parse-fn (fn [filepath]
                 (-> (with-open [r (io/reader (io/file filepath))]
                       (json/parse-stream r (partial keyword nil)))
                     keywordize-status
                     (update :config config/ensure-defaults)))]]
   storage-options))

(defn backoff-opts
  [tag]
  [[nil
    (format "--%s-backoff-budget BUDGET" tag)
    (format "%s LRS Retry Backoff Budget in ms" (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 10000]
   [nil
    (format "--%s-backoff-max-attempt MAX" tag)
    (format "%s LRS Retry Backoff Max Attempts, set to -1 for no retry"
            (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [#(<= -1 %) "Must be -1 or greater"]
    :default 10]
   [nil
    (format "--%s-backoff-j-range RANGE" tag)
    (format "%s LRS Retry Backoff Jitter Range in ms" (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [nat-int? "Must be a natural integer"]]
   [nil
    (format "--%s-backoff-initial INITIAL" tag)
    (format "%s LRS Retry Backoff Initial Delay" (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]])

;; a set for filtering
(def valid-get-params
  #{:agent
    :verb
    :activity
    :registration
    :related_activities
    :related_agents
    :since
    :until
    :format})

(def source-options
  (into
   [[nil "--source-url URL" "Source LRS xAPI Endpoint"
     :validate [not-empty "Source LRS URL Required"]]
    [nil "--source-batch-size SIZE" "Source LRS GET limit param"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]
     :default 50]
    [nil "--source-poll-interval INTERVAL" "Source LRS GET poll timeout"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]
     :default 1000]
    ["-p" "--xapi-get-param KEY=VALUE" "xAPI GET Parameters"
     :id :get-params
     :default {}
     :multi true
     :validate [not-empty "Must provide param KEY=VALUE"]
     :update-fn
     (fn [m v]
       (let [[k v] (cs/split v #"=" 2)
             kk (keyword k)]
         (if (contains? valid-get-params
                        kk)
           (assoc m kk
                  (if (#{:related_agents
                         :related_activities} kk)
                    (Boolean/parseBoolean v)
                    v))
           m)))]
    [nil "--source-username USERNAME" "Source LRS BASIC Auth username"]
    [nil "--source-password PASSWORD" "Source LRS BASIC Auth password"]]
   (backoff-opts "source")))

(def target-options
  (into [[nil "--target-url URL" "Target LRS xAPI Endpoint"
          :validate [not-empty "Target LRS URL Required"]]
         [nil "--target-batch-size SIZE" "Target LRS POST desired batch size"
          :parse-fn #(Long/parseLong %)
          :validate [pos-int? "Must be a positive integer"]
          :default 50]
         [nil "--target-username USERNAME" "Target LRS BASIC Auth username"]
         [nil "--target-password PASSWORD" "Target LRS BASIC Auth password"]]
        (backoff-opts "target")))

(def job-options
  [[nil "--get-buffer-size SIZE" "Size of GET response buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 10]
   [nil "--batch-timeout TIMEOUT" "Msecs to wait for a fully formed batch"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 200]
   ;; Filter options
   [nil "--template-profile-url URL" "Profile URL/location from which to apply statement template filters"
    :id :filter-template-profile-urls
    :multi true
    :default []
    :update-fn conj]
   [nil "--template-id IRI" "Statement template IRIs to filter on"
    :id :filter-template-ids
    :multi true
    :default []
    :update-fn conj]
   [nil "--pattern-profile-url URL" "Profile URL/location from which to apply statement pattern filters"
    :id :filter-pattern-profile-urls
    :multi true
    :default []
    :update-fn conj]
   [nil "--pattern-id IRI" "Pattern IRIs to filter on"
    :id :filter-pattern-ids
    :multi true
    :default []
    :update-fn conj]
   [nil "--statement-buffer-size SIZE" "Desired size of statement buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]
   [nil "--batch-buffer-size SIZE" "Desired size of statement batch buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]])

(defn args->options
  [args]
  (let [{:keys [errors]
         :as ret} (cli/parse-opts args
                                  (concat
                                   common-options
                                   source-options
                                   target-options
                                   job-options))]
    (if (not-empty errors)
      (throw (ex-info (format "Options Error: %s"
                              (cs/join \, errors))
                      {:type ::options-error
                       :ret ret}))
      ret)))

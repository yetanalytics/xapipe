(ns com.yetanalytics.xapipe.cli.options
  "clojure.tools.cli options for xapipe CLI"
  (:require [clojure.string :as cs]))

(def common-options
  [["-h" "--help" "Show the help and options summary"
    :default false]
   ["-s" "--storage STORAGE" "Select storage backend, noop (default) or redis"
    :default :noop
    :parse-fn keyword
    :validate [#{:noop
                 :redis} "Must be: noop | redis"]]
   ;; Redis Backend Options
   ;; TODO: auth, or just let them pass the redis url
   [nil "--redis-host HOST" "Redis Host"
    :default "0.0.0.0"]
   [nil "--redis-port PORT" "Redis Port"
    :default 6379
    :parse-fn #(Long/parseLong %)]
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
    :validate [pos-int? "Must be a positive integer"]]])

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
   [[nil "--source-batch-size SIZE" "Source LRS GET limit param"
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
  (into [[nil "--target-batch-size SIZE" "Target LRS POST desired batch size"
          :parse-fn #(Long/parseLong %)
          :validate [pos-int? "Must be a positive integer"]
          :default 50]
         [nil "--target-username USERNAME" "Target LRS BASIC Auth username"]
         [nil "--target-password PASSWORD" "Target LRS BASIC Auth password"]]
        (backoff-opts "target")))


(def job-id-option
  [nil "--job-id ID" "Job ID"])

(def show-job-option
  [nil "--show-job" "Show the job and exit"
   :default false])

(def job-options
  [[nil "--get-buffer-size SIZE" "Size of GET response buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 10]
   [nil "--get-proc-conc SIZE" "Concurrency of get req processing"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 1]
   [nil "--batch-timeout TIMEOUT" "Msecs to wait for a fully formed batch"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 200]
   ;; No defaults, are set if not present
   job-id-option
   [nil "--statement-buffer-size SIZE" "Desired size of statement buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]
   [nil "--batch-buffer-size SIZE" "Desired size of statement batch buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]
   show-job-option])

(def start-options
  (into []
        (concat
         job-options
         source-options
         target-options)))

(def resume-options
  [show-job-option])

(def retry-options
  [show-job-option])

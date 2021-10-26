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
   [nil "--redis-host HOST" "Redis Host"
    :default "0.0.0.0"]
   [nil "--redis-port PORT" "Redis Port"
    :default 6379
    :parse-fn #(Long/parseLong %)]])

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
  [[nil "--source-batch-size SIZE" "Source LRS GET limit param"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 50]
   [nil "--source-xapi-prefix PREFIX" "xAPI endpoint prefix on Source LRS"
    :validate [(fn [^String s]
                 (.startsWith s "/")) "Must start with a slash"]
    :default "/xapi"]
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
   [nil "--source-password PASSWORD" "Source LRS BASIC Auth password"]])

(def target-options
  [[nil "--target-batch-size SIZE" "Target LRS POST desired batch size"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 50]
   [nil "--target-xapi-prefix PREFIX" "xAPI endpoint prefix on Target LRS"
    :validate [(fn [^String s]
                 (.startsWith s "/")) "Must start with a slash"]
    :default "/xapi"]
   [nil "--target-username USERNAME" "Target LRS BASIC Auth username"]
   [nil "--target-password PASSWORD" "Target LRS BASIC Auth password"]])


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

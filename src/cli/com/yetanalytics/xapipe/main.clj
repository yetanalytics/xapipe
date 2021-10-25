(ns com.yetanalytics.xapipe.main
  (:require [clojure.string :as cs]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store.impl.noop :as noop-store]
            [com.yetanalytics.xapipe.store.impl.redis :as redis-store])
  (:gen-class))

(def common-options
  [["-h" "--help"
    :default false]])

(def source-options
  [[nil "--source-batch-size SOURCE_BATCH_SIZE" "Source LRS GET limit param"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 50]
   [nil "--source-xapi-prefix SOURCE_XAPI_PREFIX" "xAPI endpoint prefix on Source LRS"
    :validate [(fn [^String s]
                 (.startsWith s "/")) "Must start with a slash"]
    :default "/xapi"]
   [nil "--source-poll-interval SOURCE_POLL_INTERVAL" "Source LRS GET poll timeout"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 1000]])

(def target-options
  [[nil "--target-batch-size TARGET_BATCH_SIZE" "Target LRS POST desired batch size"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 50]
   [nil "--target-xapi-prefix TARGET_XAPI_PREFIX" "xAPI endpoint prefix on Target LRS"
    :validate [(fn [^String s]
                 (.startsWith s "/")) "Must start with a slash"]
    :default "/xapi"]])

;; statement-buffer-size
;; batch-buffer-size


(def start-options
  (into [
         [nil "--get-buffer-size SIZE" "Size of GET response buffer"
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
         ]
        (concat
         [[nil "--job-id ID" "Job ID"]]
         source-options
         target-options)))

(def resume-options
  [])

(def retry-options
  [])

(defn main*
  ([]
   {:status 1
    :message "\nusage: (start|resume|retry) (verb args) & options\n"})
  ([verb & args]
   (case verb
     "start"
     (let [{[source-url-base
             target-url-base
             & rest-args] :arguments
            opts-summary  :summary
            :keys         [options
                           errors]} (cli/parse-opts
                                     args
                                     (into common-options
                                           start-options))
           summary
           (str "start <source-url-base> <target-url-base> & options:\n"
                opts-summary)]
       (cond
         (:help options) {:status 0
                          :message summary}
         (not-empty errors) {:status 1
                             :message (cs/join \, errors)}
         (empty? source-url-base)
         {:status 1
          :message (str "source-url-base required!\n"
                        summary)}
         (empty? target-url-base)
         {:status 1
          :message (str "target-url-base required!\n"
                        summary)}
         ;; Minimum required to try a job!
         :else
         (let [;; options -> config
               {:keys [job-id
                       source-batch-size
                       source-xapi-prefix
                       source-poll-interval

                       target-batch-size
                       target-xapi-prefix

                       get-buffer-size
                       get-proc-conc
                       batch-timeout

                       statement-buffer-size
                       batch-buffer-size]} options
               config (cond-> {:get-buffer-size get-buffer-size
                               :get-proc-conc get-proc-conc
                               :batch-timeout batch-timeout
                               :source
                               {:request-config {:url-base    source-url-base
                                                 :xapi-prefix source-xapi-prefix}
                                :get-params     {}
                                :poll-interval  source-poll-interval
                                :batch-size     source-batch-size}
                               :target
                               {:request-config {:url-base    target-url-base
                                                 :xapi-prefix target-xapi-prefix}
                                :batch-size     target-batch-size}}
                        statement-buffer-size
                        (assoc :statement-buffer-size statement-buffer-size)
                        batch-buffer-size
                        (assoc :batch-buffer-size batch-buffer-size))]
           )))
     nil {:status 1
          :message "\nusage: (start|resume|retry) (verb args) & options\n"}
     {:status 1
      :message "\nusage: (start|resume|retry) (verb args) & options\n"})))

(defn- print-err
  [x]
  (binding [*out* *err*]
    (print x)))

(defn -main [& args]
  (let [{:keys [status message]} (apply main* args)]
    (if (zero? status)
      (do
        (when (not-empty message)
          (print message))
        (System/exit 0))
      (do
        (when (not-empty message)
          (print message))
        (System/exit status)))))

(comment


  (main* "start" "http://0.0.0.0:8080" "http://0.0.0.0:8081"
         )
  )

(ns com.yetanalytics.xapipe.main
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as cs]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store.impl.noop :as noop-store]
            [com.yetanalytics.xapipe.store.impl.redis :as redis-store]
            [xapi-schema.spec.resources :as xsr])
  (:gen-class))

(def common-options
  [["-h" "--help" "Show the help and options summary"
    :default false]])

;; xAPI partial GET params
;; TODO: Move spec
(s/def ::partial-get-params
  (s/keys :opt-un [:xapi.statements.GET.request.params/agent
                   :xapi.statements.GET.request.params/verb
                   :xapi.statements.GET.request.params/activity
                   :xapi.statements.GET.request.params/registration
                   :xapi.statements.GET.request.params/related_activities
                   :xapi.statements.GET.request.params/related_agents
                   :xapi.statements.GET.request.params/since
                   :xapi.statements.GET.request.params/until
                   :xapi.statements.GET.request.params/format]))

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
          m)))]])

(def target-options
  [[nil "--target-batch-size TARGET_BATCH_SIZE" "Target LRS POST desired batch size"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 50]
   [nil "--target-xapi-prefix TARGET_XAPI_PREFIX" "xAPI endpoint prefix on Target LRS"
    :validate [(fn [^String s]
                 (.startsWith s "/")) "Must start with a slash"]
    :default "/xapi"]])

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
   [nil "--job-id ID" "Job ID"]
   [nil "--statement-buffer-size" "Desired size of statement buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]
   [nil "--batch-buffer-size" "Desired size of statement batch buffer"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]
   [nil "--show-job" "Show the job and exit"
    :default false]])

(def start-options
  (into []
        (concat
         job-options
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
         (not (s/valid? ::partial-get-params
                        (:get-params options)))
         {:status 1
          :message (str "invalid xAPI params:\n"
                        (s/explain-str
                         ::partial-get-params
                         (:get-params options)))}
         ;; Minimum required to try a job!
         :else
         (let [
               ;; options -> config
               {:keys [job-id
                       source-batch-size
                       source-xapi-prefix
                       source-poll-interval
                       get-params

                       target-batch-size
                       target-xapi-prefix

                       get-buffer-size
                       get-proc-conc
                       batch-timeout

                       statement-buffer-size
                       batch-buffer-size

                       show-job]} options
               config (cond-> {:get-buffer-size get-buffer-size
                               :get-proc-conc get-proc-conc
                               :batch-timeout batch-timeout
                               :source
                               {:request-config {:url-base    source-url-base
                                                 :xapi-prefix source-xapi-prefix}
                                :get-params     get-params
                                :poll-interval  source-poll-interval
                                :batch-size     source-batch-size}
                               :target
                               {:request-config {:url-base    target-url-base
                                                 :xapi-prefix target-xapi-prefix}
                                :batch-size     target-batch-size}}
                        statement-buffer-size
                        (assoc :statement-buffer-size statement-buffer-size)
                        batch-buffer-size
                        (assoc :batch-buffer-size batch-buffer-size))
               job-id (or job-id
                          (.toString (java.util.UUID/randomUUID)))
               job (job/init-job job-id config)]
           (if (true? show-job)
             {:status 0
              :message (pr-str job)}
             (let [;; TODO: more store
                   store (noop-store/new-store)]
               (try
                 (let [{:keys [states]
                        stop :stop-fn} (xapipe/run-job job)]
                   (.addShutdownHook (Runtime/getRuntime)
                                     (Thread. ^Runnable stop))
                   (let [{{:keys [status]} :state
                          :as job-result} (-> states
                                              (xapipe/log-states :info)
                                              (xapipe/store-states store)
                                              a/<!!)]
                     {:status (if (= :error status)
                                1
                                0)}))
                 (catch Exception ex
                   (log/error ex "Runtime Exception")
                   {:status 1
                    :message (ex-message ex)}))))

           )))
     nil {:status 1
          :message "\nusage: (start|resume|retry) (verb args) & options\n"}
     {:status 1
      :message "\nusage: (start|resume|retry) (verb args) & options\n"})))

(defn -main [& args]
  (let [{:keys [status message]} (apply main* args)]
    (if (zero? status)
      (do
        (when (not-empty message)
          (log/info message))
        (System/exit 0))
      (do
        (when (not-empty message)
          (log/error message))
        (System/exit status)))))

(comment

  (main* "start" "http://0.0.0.0:8080" "http://0.0.0.0:8081"
         "-p" "related_agents=true"
         "--show-job")
  )

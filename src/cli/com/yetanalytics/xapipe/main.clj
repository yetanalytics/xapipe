(ns com.yetanalytics.xapipe.main
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as cs]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store.impl.noop :as noop-store]
            [com.yetanalytics.xapipe.store.impl.redis :as redis-store]
            [xapi-schema.spec.resources :as xsr])
  (:import [java.net URL])
  (:gen-class))

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
          m)))]
   [nil "--source-username" "Source LRS BASIC Auth username"]
   [nil "--source-password" "Source LRS BASIC Auth password"]])

(def target-options
  [[nil "--target-batch-size TARGET_BATCH_SIZE" "Target LRS POST desired batch size"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 50]
   [nil "--target-xapi-prefix TARGET_XAPI_PREFIX" "xAPI endpoint prefix on Target LRS"
    :validate [(fn [^String s]
                 (.startsWith s "/")) "Must start with a slash"]
    :default "/xapi"]
   [nil "--target-username" "Target LRS BASIC Auth username"]
   [nil "--target-password" "Target LRS BASIC Auth password"]])

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

;; Verbs

;; Start
(defn- parse-lrs-url
  [^String url]
  (try
    (let [^URL parsed (URL. url)]
      {:url-base (format
                  "%s://%s"
                  (.getProtocol parsed)
                  (.getAuthority parsed))
       :xapi-prefix (.getPath parsed)})
    (catch Exception _
      nil)))

(defn- start
  "Start a job, overwriting any previously with that id"
  [args]
  (let [{[source-url
          target-url
          & rest-args] :arguments
         opts-summary  :summary
         :keys         [options
                        errors]} (cli/parse-opts
                                  args
                                  (into common-options
                                        start-options))
        summary
        (str "start <source-url> <target-url> & options:\n"
             opts-summary)
        source-req-config (parse-lrs-url source-url)
        target-req-config (parse-lrs-url target-url)]
    (cond
      ;; param errors
      (not-empty errors) {:status 1
                          :message (cs/join \, errors)}
      ;; user requested help
      (:help options) {:status 0
                       :message summary}
      ;; no source
      (nil? source-req-config)
      {:status 1
       :message (str "source-url not present or invalid\n"
                     summary)}
      ;; no target
      (nil? target-req-config)
      {:status 1
       :message (str "target-url not present or invalid\n"
                     summary)}
      ;; invalid xapi params
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
                    source-username
                    source-password

                    target-batch-size
                    target-xapi-prefix
                    target-username
                    target-password

                    get-buffer-size
                    get-proc-conc
                    batch-timeout

                    statement-buffer-size
                    batch-buffer-size

                    storage
                    redis-host
                    redis-port

                    show-job]} options
            config (cond-> {:get-buffer-size get-buffer-size
                            :get-proc-conc get-proc-conc
                            :batch-timeout batch-timeout
                            :source
                            {:request-config (cond-> source-req-config
                                               (and source-username
                                                    source-password)
                                               (assoc :username source-username
                                                      :password source-password))
                             :get-params     get-params
                             :poll-interval  source-poll-interval
                             :batch-size     source-batch-size}
                            :target
                            {:request-config (cond-> target-req-config
                                               (and target-username
                                                    target-password)
                                               (assoc :username target-username
                                                      :password target-password))
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
          (let [store (case storage
                        :noop (noop-store/new-store)
                        :redis (redis-store/new-store
                                ;; TODO: Pool?
                                {:pool {}
                                 :spec
                                 {:uri (format "redis://%s:%d"
                                               redis-host
                                               redis-port)}}))]
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
                 :message (ex-message ex)}))))))))

(defn- resume
  "Resume a job by ID, clearing errors"
  [args]
  {:status 1
   :message "Not yet implemented!"})

(defn- retry
  "Resume a job by ID, clearing errors"
  [args]
  {:status 1
   :message "Not yet implemented!"})

(def top-level-summary
  "usage: (start|resume|retry) (verb args) (--help)")

(def bad-verb-resp
  {:status 1
   :message top-level-summary})

(defn- main*
  ([] bad-verb-resp)
  ([verb & args]
   (case verb
     "start" (start args)
     "resume" (resume args)
     "retry" (retry args)
     "--help" {:status 0
               :message top-level-summary}
     nil bad-verb-resp
     bad-verb-resp)))

(defn -main [& args]
  (let [{:keys [status message]}
        (try
          (apply main* args)
          (catch Exception ex
            {:status 1
             :message (ex-message ex)}))]
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

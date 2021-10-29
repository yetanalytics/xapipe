(ns com.yetanalytics.xapipe.main
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as cs]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.cli.options :refer [common-options
                                                         source-options
                                                         target-options
                                                         start-options
                                                         resume-options
                                                         retry-options]]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.noop :as noop-store]
            [com.yetanalytics.xapipe.store.impl.redis :as redis-store]
            [xapi-schema.spec.resources :as xsr])
  (:import [java.net URL])
  (:gen-class))

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

;; helpers
(defn- create-store
  [{:keys [storage
           redis-host
           redis-port]}]
  (case storage
    :noop (noop-store/new-store)
    :redis (redis-store/new-store
            ;; TODO: Pool?
            {:pool {}
             :spec
             {:uri (format "redis://%s:%d"
                           redis-host
                           redis-port)}})))

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

(defn- force-stop-job!
  "Given a stop-fn and states channel, finish and stop the job.
  *BLOCKING*"
  [stop-fn states]
  (when (stop-fn)
    (a/<!! (a/into [] states))))

(defn- handle-job
  "Actually execute a job, wrapping result"
  [store job client-opts]
  (try
    (let [{:keys [states]
           stop :stop-fn} (xapipe/run-job job client-opts)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable
                                 (fn []
                                   (force-stop-job! stop states))))
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
       :message (ex-message ex)})))

(defn- options->client-opts
  [{:keys [conn-timeout
           conn-threads
           conn-default-per-route
           conn-insecure?
           conn-io-thread-count]}]
  {:conn-mgr-opts
   (cond-> {}
     conn-timeout (assoc :timeout conn-timeout)
     conn-threads (assoc :threads conn-threads)
     conn-default-per-route
     (assoc :default-per-route conn-default-per-route)
     conn-insecure? (assoc :insecure? conn-insecure?)
     conn-io-thread-count
     (assoc-in [:io-config :io-thread-count]
               conn-io-thread-count))})

(defn- options->config
  [{:keys [job-id
           source-batch-size
           source-poll-interval
           get-params
           source-username
           source-password
           source-backoff-budget
           source-backoff-max-attempt
           source-backoff-j-range
           source-backoff-initial

           target-batch-size
           target-username
           target-password
           target-backoff-budget
           target-backoff-max-attempt
           target-backoff-j-range
           target-backoff-initial

           get-buffer-size
           batch-timeout

           statement-buffer-size
           batch-buffer-size]}
   source-req-config
   target-req-config]
  (cond-> {:get-buffer-size get-buffer-size
           :batch-timeout batch-timeout
           :source
           {:request-config (cond-> source-req-config
                              (and source-username
                                   source-password)
                              (assoc :username source-username
                                     :password source-password))
            :get-params     get-params
            :poll-interval  source-poll-interval
            :batch-size     source-batch-size
            :backoff-opts
            (cond-> {:budget source-backoff-budget
                     :max-attempt source-backoff-max-attempt}
              source-backoff-j-range
              (assoc :j-range source-backoff-j-range)
              source-backoff-initial
              (assoc :initial source-backoff-initial))}
           :target
           {:request-config (cond-> target-req-config
                              (and target-username
                                   target-password)
                              (assoc :username target-username
                                     :password target-password))
            :batch-size     target-batch-size
            :backoff-opts
            (cond-> {:budget target-backoff-budget
                     :max-attempt target-backoff-max-attempt}
              target-backoff-j-range
              (assoc :j-range target-backoff-j-range)
              target-backoff-initial
              (assoc :initial target-backoff-initial))}}
    statement-buffer-size
    (assoc :statement-buffer-size statement-buffer-size)
    batch-buffer-size
    (assoc :batch-buffer-size batch-buffer-size)))

;; Verbs

;; Start
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
      (let [config (options->config
                    options
                    source-req-config
                    target-req-config)
            job-id (or (:job-id options)
                       (.toString (java.util.UUID/randomUUID)))
            job (job/init-job job-id config)]
        (if (true? (:show-job options))
          {:status 0
           :message (pr-str job)}
          (let [store (create-store options)]
            (handle-job store job (options->client-opts options))))))))

(defn- resume
  "Resume a job by ID, clearing errors"
  [args]
  (let [{[job-id] :arguments
         opts-summary  :summary
         :keys         [options
                        errors]} (cli/parse-opts
                                  args
                                  (into common-options
                                        resume-options))
        summary (str "resume <job-id> & options:\n"
                     opts-summary)]
    (cond
      (:help options) {:status 0
                       :message summary}
      (or (nil? job-id)
          (empty? job-id))
      {:status 1
       :message summary}

      (= (:storage options) :noop)
      {:status 1
       :message (str "resume not possible without storage. Use -s redis\n"
                     opts-summary)}
      :else
      (let [store (create-store options)]
        (if-let [job (store/read-job store job-id)]
          (if (:show-job options)
            {:status 0
             :message (pr-str job)}
            (handle-job store
                        job
                        (options->client-opts options)))
          {:status 1
           :message (format "Job %s not found!" job-id)})))))

(defn- retry
  "Resume a job by ID, clearing errors"
  [args]
  (let [{[job-id] :arguments
         opts-summary  :summary
         :keys         [options
                        errors]} (cli/parse-opts
                                  args
                                  (into common-options
                                        retry-options))
        summary (str "retry <job-id> & options:\n"
                     opts-summary)]
    (cond
      (:help options) {:status 0
                       :message summary}
      (or (nil? job-id)
          (empty? job-id))
      {:status 1
       :message summary}

      (= (:storage options) :noop)
      {:status 1
       :message (str "retry not possible without storage. Use -s redis\n"
                     opts-summary)}
      :else
      (let [store (create-store options)]
        (if-let [job (store/read-job store job-id)]
          (if (:show-job options)
            {:status 0
             :message (pr-str job)}
            (handle-job store
                        (-> job
                            (update :state state/clear-errors)
                            (update :state state/set-status :paused))
                        (options->client-opts options)))
          {:status 1
           :message (format "Job %s not found!" job-id)})))))

(def top-level-summary
  "usage: (start|resume|retry) (verb args) (--help)")

(def bad-verb-resp
  {:status 1
   :message top-level-summary})

(defn main*
  ([] bad-verb-resp)
  ([verb & args]
   (case verb
     "start" (start args)
     "resume" (resume args)
     "retry" (retry args)
     "--help" {:status 0
               :message
               (cs/join "\n" (map :message
                                  [(start ["--help"])
                                   (resume ["--help"])
                                   (retry ["--help"])]))}
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

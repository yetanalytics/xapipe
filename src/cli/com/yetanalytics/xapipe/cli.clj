(ns com.yetanalytics.xapipe.cli
  "CLI helper functions"
  (:require [clojure.core.async :as a]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.cli.options :as opts]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.config :as config]
            [com.yetanalytics.xapipe.metrics :as metrics]
            [com.yetanalytics.xapipe.metrics.impl.prometheus :as pro]
            [com.yetanalytics.xapipe.spec.common :as cspec]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.noop :as noop-store]
            [com.yetanalytics.xapipe.store.impl.redis :as redis-store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem-store]
            [com.yetanalytics.xapipe.store.impl.file :as file-store]
            [xapi-schema.spec.resources :as xsr])
  (:import [java.net URL]))

;; xAPI partial GET params
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

(s/fdef create-store
  :args (s/cat :options ::opts/all-options)
  :ret :com.yetanalytics.xapipe/store)

(defn create-store
  [{:keys [storage
           redis-uri
           redis-prefix
           file-store-dir]}]
  (case storage
    :noop (noop-store/new-store)
    :redis (redis-store/new-store
            ;; TODO: Pool?
            {:pool {}
             :spec
             {:uri redis-uri}}
            redis-prefix)
    :mem (mem-store/new-store)
    :file (file-store/new-store file-store-dir)))

(s/fdef create-reporter
  :args (s/cat :job-id ::job/id
               :options ::opts/all-options)
  :ret ::metrics/reporter)

(defn create-reporter
  [job-id
   {:keys [metrics-reporter
           prometheus-push-gateway]}]
  (case metrics-reporter
    "prometheus" (pro/prometheus-push-reporter prometheus-push-gateway job-id)
    "noop" (metrics/->NoopReporter)))

(s/fdef parse-lrs-url
  :args (s/cat :url string?)
  :ret ::client/request-config)

(defn parse-lrs-url
  [^String url]
  (try
    (let [^URL parsed (URL. url)]
      {:url-base (format
                  "%s://%s"
                  (.getProtocol parsed)
                  (.getAuthority parsed))
       :xapi-prefix (.getPath parsed)})
    (catch Exception ex
      (throw (ex-info (format "Could not parse LRS URL %s" url))))))

(s/fdef force-stop-job!
  :args (s/cat :stop-fn fn?
               :states ::cspec/channel)
  :ret ::client/request-config)

(defn force-stop-job!
  "Given a stop-fn and states channel, finish and stop the job.
  *BLOCKING*"
  [stop-fn states]
  (when (stop-fn)
    (a/<!! (a/into [] states))))

;; Command exit status
(s/def ::status #{0 1})

;; Message, a string
(s/def ::message (s/nilable string?))

(s/def ::exit
  (s/keys :req-un [::status]
          :opt-un [::message]))

(s/fdef handle-job
  :args (s/cat :store :com.yetanalytics.xapipe/store
               :job ::xapipe/job
               :client-opts ::client/http-client-opts
               :reporter ::metrics/reporter)
  :ret ::exit)

(defn handle-job
  "Actually execute a job, wrapping result
  Redef this when testing for cooler output"
  [store job client-opts reporter]
  (try
    (let [{:keys [states]
           stop :stop-fn} (xapipe/run-job job
                                          :reporter
                                          reporter
                                          :client-opts
                                          client-opts)]
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

(s/fdef options->client-opts
  :args (s/cat :options ::opts/all-options)
  :ret (s/keys :req-un [::client/conn-mgr-opts]))

(defn options->client-opts
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

(s/fdef options->config
  :args (s/cat :options ::opts/all-options)
  :ret ::job/config)

(defn options->config
  [{:keys [job-id

           source-url

           source-batch-size
           source-poll-interval
           get-params
           source-username
           source-password
           source-backoff-budget
           source-backoff-max-attempt
           source-backoff-j-range
           source-backoff-initial

           target-url

           target-batch-size
           target-username
           target-password
           target-backoff-budget
           target-backoff-max-attempt
           target-backoff-j-range
           target-backoff-initial

           get-buffer-size
           batch-timeout

           filter-template-profile-urls
           filter-template-ids
           filter-pattern-profile-urls
           filter-pattern-ids
           filter-ensure-paths
           filter-match-paths
           filter-concept-profile-urls
           filter-concept-types
           filter-activity-type-ids
           filter-verb-ids
           filter-attachment-usage-types

           statement-buffer-size
           batch-buffer-size]}]
  (cond-> {:get-buffer-size get-buffer-size
           :batch-timeout batch-timeout
           :source
           {:request-config (cond-> (parse-lrs-url source-url)
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
           {:request-config (cond-> (parse-lrs-url target-url)
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
              (assoc :initial target-backoff-initial))}
           :filter {}}
    statement-buffer-size
    (assoc :statement-buffer-size statement-buffer-size)

    batch-buffer-size
    (assoc :batch-buffer-size batch-buffer-size)

    (not-empty filter-template-profile-urls)
    (assoc-in [:filter :template] {:profile-urls filter-template-profile-urls
                                   :template-ids (into []
                                                       filter-template-ids)})

    (not-empty filter-pattern-profile-urls)
    (assoc-in [:filter :pattern] {:profile-urls filter-pattern-profile-urls
                                  :pattern-ids (into []
                                                     filter-pattern-ids)})

    (not-empty filter-ensure-paths)
    (assoc-in [:filter :path :ensure-paths] filter-ensure-paths)

    (not-empty filter-match-paths)
    (assoc-in [:filter :path :match-paths] filter-match-paths)

    (or (not-empty filter-concept-profile-urls)
        (not-empty filter-activity-type-ids)
        (not-empty filter-verb-ids)
        (not-empty filter-attachment-usage-types))
    (assoc-in [:filter :concept] {:profile-urls
                                  (into [] filter-concept-profile-urls)
                                  :concept-types
                                  (into [] filter-concept-types)
                                  :activity-type-ids
                                  (into [] filter-activity-type-ids)
                                  :verb-ids
                                  (into [] filter-verb-ids)
                                  :attachment-usage-types
                                  (into [] filter-attachment-usage-types)})))

(s/fdef create-job
  :args (s/cat :options ::opts/all-options)
  :ret ::xapipe/job)

(defn create-job
  "Create a new job from options or throw"
  [{:keys [source-url
           target-url]
    :as options}]
  (when (empty? source-url)
    (throw (ex-info "--source-lrs-url cannot be empty!"
                    {:type ::no-source-url})))
  (when (empty? target-url)
    (throw (ex-info "--target-lrs-url cannot be empty!"
                    {:type ::no-target-url})))
  (cond
    ;; invalid xapi params
    (not (s/valid? ::partial-get-params
                   (:get-params options)))
    (throw (ex-info (str "invalid xAPI params:\n"
                         (s/explain-str
                          ::partial-get-params
                          (:get-params options)))
                    {:type :invalid-get-params}))
    ;; Minimum required to try a job!
    :else
    (let [config (options->config options)
          job-id (or (:job-id options)
                     (.toString (java.util.UUID/randomUUID)))]
      (job/init-job job-id config))))

(s/fdef reconfigure-job
  :args (s/cat :job (s/with-gen ::xapipe/job
                      (fn []
                        (sgen/fmap
                         #(update % :config config/ensure-defaults)
                         (s/gen ::xapipe/job))))
               :options ::opts/all-options)
  :ret ::xapipe/job)

(defn reconfigure-job
  "Given an extant job and CLI options, apply any overriding options"
  [job
   {:keys [source-url
           source-username
           source-password

           source-batch-size
           source-poll-interval
           get-params
           source-backoff-budget
           source-backoff-max-attempt
           source-backoff-j-range
           source-backoff-initial

           target-url
           target-username
           target-password
           target-backoff-budget
           target-backoff-max-attempt
           target-backoff-j-range
           target-backoff-initial

           target-batch-size

           get-buffer-size
           batch-timeout

           statement-buffer-size
           batch-buffer-size]}]
  (cond-> job
    source-url
    (update-in
     [:config :source :request-config]
     merge (parse-lrs-url source-url))

    source-username
    (assoc-in
     [:config :source :request-config :username]
     source-username)

    source-password
    (assoc-in
     [:config :source :request-config :password]
     source-password)

    ;; if there's a default, only update on change
    (not= source-batch-size
          (get-in job [:config :source :batch-size]))
    (assoc-in
     [:config :source :batch-size]
     source-batch-size)

    (not= source-poll-interval
          (get-in job [:config :source :poll-interval]))
    (assoc-in
     [:config :source :poll-interval]
     source-poll-interval)

    ;; With no args, get-params is an empty map, so ignore
    (and (not-empty get-params)
         (not= get-params
               (get-in job [:config :source :get-params])))
    (assoc-in
     [:config :source :get-params]
     get-params)

    (not= source-backoff-budget
          (get-in job [:config :source :backoff-opts :budget]))
    (assoc-in
     [:config :source :backoff-opts :budget]
     source-backoff-budget)

    (not= source-backoff-max-attempt
          (get-in job [:config :source :backoff-opts :max-attempt]))
    (assoc-in
     [:config :source :backoff-opts :max-attempt]
     source-backoff-max-attempt)

    source-backoff-j-range
    (assoc-in
     [:config :source :backoff-opts :j-range]
     source-backoff-j-range)

    source-backoff-initial
    (assoc-in
     [:config :source :backoff-opts :initial]
     source-backoff-initial)

    target-url
    (update-in
     [:config :target :request-config]
     merge (parse-lrs-url target-url))

    target-username
    (assoc-in
     [:config :target :request-config :username]
     target-username)

    target-password
    (assoc-in
     [:config :target :request-config :password]
     target-password)

    target-batch-size
    (assoc-in
     [:config :target :batch-size]
     target-batch-size)

    (not= target-backoff-budget
          (get-in job [:config :target :backoff-opts :budget]))
    (assoc-in
     [:config :target :backoff-opts :budget]
     target-backoff-budget)

    (not= target-backoff-max-attempt
          (get-in job [:config :target :backoff-opts :max-attempt]))
    (assoc-in
     [:config :target :backoff-opts :max-attempt]
     target-backoff-max-attempt)

    target-backoff-j-range
    (assoc-in
     [:config :target :backoff-opts :j-range]
     target-backoff-j-range)

    target-backoff-initial
    (assoc-in
     [:config :target :backoff-opts :initial]
     target-backoff-initial)

    (not= get-buffer-size
          (get job :get-buffer-size))
    (assoc-in [:config :get-buffer-size] get-buffer-size)

    (not= batch-timeout
          (get job :batch-timeout))
    (assoc-in [:config :batch-timeout] batch-timeout)

    statement-buffer-size
    (assoc-in [:config :statement-buffer-size] statement-buffer-size)

    batch-buffer-size
    (assoc-in [:config :batch-buffer-size] batch-buffer-size)))

(s/fdef list-store-jobs
  :args (s/cat :store :com.yetanalytics.xapipe/store)
  :ret nil?)

(defn list-store-jobs
  [store]
  (doseq [[page batch] (->> (store/list-jobs store)
                           (partition-all 100)
                           (map-indexed vector))]
    (log/infof "Page %d%s"
               page
               (with-out-str
                 (pprint/print-table
                  (for [{{:keys [status
                                 cursor]} :state
                         job-id :id} batch]
                    {"job-id" job-id
                     "status" (name status)
                     "cursor" cursor}))))))

(ns com.yetanalytics.xapipe.cli
  "CLI helper functions"
  (:require [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store.impl.noop :as noop-store]
            [com.yetanalytics.xapipe.store.impl.redis :as redis-store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem-store]
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

(defn create-store
  [{:keys [storage
           redis-host
           redis-port]}]
  (case storage
    :noop (noop-store/new-store)
    :redis (if (and redis-host redis-port)
             (redis-store/new-store
              ;; TODO: Pool?
              {:pool {}
               :spec
               {:uri (format "redis://%s:%d"
                             redis-host
                             redis-port)}})
             (throw (ex-info "Redis Config Required!"
                             {:type ::redis-config-required})))
    :mem (mem-store/new-store)))

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

(defn force-stop-job!
  "Given a stop-fn and states channel, finish and stop the job.
  *BLOCKING*"
  [stop-fn states]
  (when (stop-fn)
    (a/<!! (a/into [] states))))

(defn handle-job
  "Actually execute a job, wrapping result
  Redef this when testing for cooler output"
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

(defn options->config
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

           filter-template-profile-urls
           filter-template-ids
           filter-pattern-profile-urls
           filter-pattern-ids

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
                                                     filter-pattern-ids)})))

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
  (let [source-req-config (parse-lrs-url source-url)
        target-req-config (parse-lrs-url target-url)]
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
      (let [config (options->config
                    options
                    source-req-config
                    target-req-config)
            job-id (or (:job-id options)
                       (.toString (java.util.UUID/randomUUID)))]
        (job/init-job job-id config)))))

(defn reconfigure-job
  "Given an extant job and CLI options, apply any overriding options"
  [job
   {:keys [source-url
           source-username
           source-password

           source-batch-size
           source-poll-interval
           get-params

           target-url
           target-username
           target-password

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

    (not= get-buffer-size
          (get job :get-buffer-size))
    (assoc :get-buffer-size get-buffer-size)

    (not= batch-timeout
          (get job :batch-timeout))
    (assoc :batch-timeout batch-timeout)

    statement-buffer-size
    (assoc :statement-buffer-size statement-buffer-size)

    batch-buffer-size
    (assoc :batch-buffer-size batch-buffer-size)))

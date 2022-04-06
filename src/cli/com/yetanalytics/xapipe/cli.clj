(ns com.yetanalytics.xapipe.cli
  "CLI helper functions"
  (:require [clojure.core.async :as a]
            [clojure.pprint :as pprint]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.cli.options :as opts]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.config :as config]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.metrics :as metrics]
            [com.yetanalytics.xapipe.metrics.impl.prometheus :as pro]
            [com.yetanalytics.xapipe.spec.common :as cspec]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.store.impl.file :as file-store]
            [com.yetanalytics.xapipe.store.impl.memory :as mem-store]
            [com.yetanalytics.xapipe.store.impl.noop :as noop-store]
            [com.yetanalytics.xapipe.store.impl.redis :as redis-store]
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
          :opt-un [::message
                   ::xapipe/job]))

(s/fdef errors->message
  :args (s/cat :errors ::state/errors)
  :ret string?)

(defn errors->message
  "Create a (possibly multiline) message from multiple errors"
  [errors]
  (cs/join \newline
           (for [{etype :type
                  emsg :message} errors]
             (format "%s error: %s"
                     (name etype)
                     emsg))))

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
    (let [_ (log/debugf "Running job %s" (:id job))
          {:keys [states]
           stop :stop-fn} (xapipe/run-job job
                                          :reporter
                                          reporter
                                          :client-opts
                                          client-opts)]
      (log/debugf "Adding shutdown hook for job %s" (:id job))
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable
                                 (fn []
                                   (force-stop-job! stop states))))
      (let [_ (log/debugf "Waiting for job %s" (:id job))
            {{:keys [status]} :state
             :as job-result} (-> states
                                 (xapipe/log-states :info)
                                 (xapipe/store-states store)
                                 a/<!!)
            _ (log/debugf "Finished job %s status: %s"
                          (:id job)
                          (name status))]
        {:job job-result
         :status (if (= :error status)
                   1
                   0)
         :message (if (= :error status)
                    (errors->message (job/all-errors job-result))
                    "OK")}))
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

(def option-paths
  {;; root config
   :get-buffer-size       [:get-buffer-size]
   :cleanup-buffer-size   [:cleanup-buffer-size]
   :statement-buffer-size [:statement-buffer-size]
   :batch-buffer-size     [:batch-buffer-size]
   :batch-timeout         [:batch-timeout]

   ;; Source LRS
   :source-batch-size          [:source :batch-size]
   :source-poll-interval       [:source :poll-interval]
   :get-params                 [:source :get-params]
   :source-username            [:source :request-config :username]
   :source-password            [:source :request-config :password]
   :source-auth-uri            [:source :request-config :oauth-params :auth-uri]
   :source-client-id           [:source :request-config :oauth-params :client-id]
   :source-client-secret       [:source :request-config :oauth-params :client-secret]
   :source-scope               [:source :request-config :oauth-params :scope]
   :source-token               [:source :request-config :token]
   :source-backoff-budget      [:source :backoff-opts :budget]
   :source-backoff-max-attempt [:source :backoff-opts :max-attempt]
   :source-backoff-j-range     [:source :backoff-opts :j-range]
   :source-backoff-initial     [:source :backoff-opts :initial]

   ;; Target LRS
   :target-batch-size          [:target :batch-size]
   :target-poll-interval       [:target :poll-interval]
   :target-username            [:target :request-config :username]
   :target-password            [:target :request-config :password]
   :target-auth-uri            [:target :request-config :oauth-params :auth-uri]
   :target-client-id           [:target :request-config :oauth-params :client-id]
   :target-client-secret       [:target :request-config :oauth-params :client-secret]
   :target-scope               [:target :request-config :oauth-params :scope]
   :target-token               [:target :request-config :token]
   :target-backoff-budget      [:target :backoff-opts :budget]
   :target-backoff-max-attempt [:target :backoff-opts :max-attempt]
   :target-backoff-j-range     [:target :backoff-opts :j-range]
   :target-backoff-initial     [:target :backoff-opts :initial]

   ;; Filters
   :filter-template-profile-urls  [:filter :template :profile-urls]
   :filter-template-ids           [:filter :template :template-ids]
   :filter-pattern-profile-urls   [:filter :pattern :profile-urls]
   :filter-pattern-ids            [:filter :pattern :pattern-ids]
   :filter-ensure-paths           [:filter :path :ensure-paths]
   :filter-match-paths            [:filter :path :match-paths]
   :filter-concept-profile-urls   [:filter :concept :profile-urls]
   :filter-concept-types          [:filter :concept :concept-types]
   :filter-activity-type-ids      [:filter :concept :activity-type-ids]
   :filter-verb-ids               [:filter :concept :verb-ids]
   :filter-attachment-usage-types [:filter :concept :attachment-usage-types]})

(s/fdef options->config
  :args (s/cat :options ::opts/all-options)
  :ret ::job/config)

(defn options->config
  [{:keys [source-url
           target-url]
    :as options}]
  (reduce-kv
   (fn [m k v]
     (if-let [path (get option-paths k)]
       (if (= :filter (first path))
         ;; filters take collections
         (if (not-empty v)
           (assoc-in m path v)
           m)
         ;; All other opts are scalar
         (assoc-in m path v))
       ;; ignore unknown
       m))
   {:source {:request-config (parse-lrs-url source-url)}
    :target {:request-config (parse-lrs-url target-url)}
    :filter {}}
   options))

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

(s/fdef only-auth
  :args (s/cat
         :config (s/with-gen ::job/config
                   (fn []
                     (sgen/fmap
                      config/ensure-defaults
                      (s/gen ::job/config))))
         :lrs-type #{:source :target}
         :auth-type #{:basic :token :oauth})
  :ret ::xapipe/job)

(defn only-auth
  "Given a job config, LRS type :source or :target and auth type of :basic,
  :token, or :oauth, remove other auth from the LRS."
  [config
   lrs-type
   auth-type]
  (apply update-in
         config
         [lrs-type :request-config]
         dissoc
         (case auth-type
           :basic [:token :oauth-params]
           :token [:username :password :oauth-params]
           :oauth [:username :password :token])))

(s/fdef reconfigure-with-options
  :args (s/cat :config (s/with-gen ::job/config
                         (fn []
                           (sgen/fmap
                            config/ensure-defaults
                            (s/gen ::job/config))))
               :options ::opts/all-options)
  :ret ::job/config)

(defn reconfigure-with-options
  "Given an extant job and CLI options, apply any overriding options"
  [config
   {:keys [source-url
           source-username
           source-password
           source-auth-uri
           source-client-id
           source-client-secret
           source-scope
           source-token

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
           target-auth-uri
           target-client-id
           target-client-secret
           target-scope
           target-token

           target-backoff-budget
           target-backoff-max-attempt
           target-backoff-j-range
           target-backoff-initial

           target-batch-size

           get-buffer-size
           batch-timeout
           cleanup-buffer-size

           statement-buffer-size
           batch-buffer-size]}]
  (cond-> config
    source-url
    (update-in
     [:source :request-config]
     merge (parse-lrs-url source-url))

    (or source-username
        source-password)
    (-> (cond->
          source-username
          (assoc-in
           [:source :request-config :username]
           source-username)

          source-password
          (assoc-in
           [:source :request-config :password]
           source-password))
        (only-auth :source :basic))

    (or source-auth-uri
        source-client-id
        source-client-secret)
    (-> (cond->
          source-auth-uri
          (assoc-in
           [:source :request-config :oauth-params :auth-uri]
           source-auth-uri)

          source-client-id
          (assoc-in
           [:source :request-config :oauth-params :client-id]
           source-client-id)

          source-client-secret
          (assoc-in
           [:source :request-config :oauth-params :client-secret]
           source-client-secret)
          source-scope
          (assoc-in
           [:source :request-config :oauth-params :scope]
           source-scope))
        (only-auth :source :oauth))

    source-token
    (-> (assoc-in [:source :request-config :token] source-token)
        (only-auth :source :token))

    ;; if there's a default, only update on change
    (and source-batch-size
         (not= source-batch-size
               (get-in config [:source :batch-size])))
    (assoc-in
     [:source :batch-size]
     source-batch-size)

    (not= source-poll-interval
          (get-in config [:source :poll-interval]))
    (assoc-in
     [:source :poll-interval]
     source-poll-interval)

    ;; With no args, get-params is an empty map, so ignore
    (and (not-empty get-params)
         (not= get-params
               (get-in config [:source :get-params])))
    (->
     (assoc-in
      [:source :get-params]
      get-params)
     (cond->
         (and source-batch-size
              (not= source-batch-size
                    (get-in config [:source :batch-size])))
       (assoc-in
        [:source :get-params :limit]
        source-batch-size)))

    (not= source-backoff-budget
          (get-in config [:source :backoff-opts :budget]))
    (assoc-in
     [:source :backoff-opts :budget]
     source-backoff-budget)

    (not= source-backoff-max-attempt
          (get-in config [:source :backoff-opts :max-attempt]))
    (assoc-in
     [:source :backoff-opts :max-attempt]
     source-backoff-max-attempt)

    source-backoff-j-range
    (assoc-in
     [:source :backoff-opts :j-range]
     source-backoff-j-range)

    source-backoff-initial
    (assoc-in
     [:source :backoff-opts :initial]
     source-backoff-initial)

    target-url
    (update-in
     [:target :request-config]
     merge (parse-lrs-url target-url))

    (or target-username
        target-password)
    (-> (cond->
          target-username
          (assoc-in
           [:target :request-config :username]
           target-username)

          target-password
          (assoc-in
           [:target :request-config :password]
           target-password))
        (only-auth :target :basic))

    (or target-auth-uri
        target-client-id
        target-client-secret)
    (-> (cond->
          target-auth-uri
          (assoc-in
           [:target :request-config :oauth-params :auth-uri]
           target-auth-uri)

          target-client-id
          (assoc-in
           [:target :request-config :oauth-params :client-id]
           target-client-id)

          target-client-secret
          (assoc-in
           [:target :request-config :oauth-params :client-secret]
           target-client-secret)
          target-scope
          (assoc-in
           [:target :request-config :oauth-params :scope]
           target-scope))
        (only-auth :target :oauth))

    target-token
    (-> (assoc-in [:target :request-config :token] target-token)
        (only-auth :target :token))

    target-batch-size
    (assoc-in
     [:target :batch-size]
     target-batch-size)

    (not= target-backoff-budget
          (get-in config [:target :backoff-opts :budget]))
    (assoc-in
     [:target :backoff-opts :budget]
     target-backoff-budget)

    (not= target-backoff-max-attempt
          (get-in config [:target :backoff-opts :max-attempt]))
    (assoc-in
     [:target :backoff-opts :max-attempt]
     target-backoff-max-attempt)

    target-backoff-j-range
    (assoc-in
     [:target :backoff-opts :j-range]
     target-backoff-j-range)

    target-backoff-initial
    (assoc-in
     [:target :backoff-opts :initial]
     target-backoff-initial)

    (not= get-buffer-size
          (get config :get-buffer-size))
    (assoc :get-buffer-size get-buffer-size)

    (not= batch-timeout
          (get config :batch-timeout))
    (assoc :batch-timeout batch-timeout)

    statement-buffer-size
    (assoc :statement-buffer-size statement-buffer-size)

    batch-buffer-size
    (assoc :batch-buffer-size batch-buffer-size)

    cleanup-buffer-size
    (assoc :cleanup-buffer-size cleanup-buffer-size)))

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

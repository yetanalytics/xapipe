(ns com.yetanalytics.xapipe
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.client.multipart-mixed :as mm]
            [com.yetanalytics.xapipe.filter :as filt]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.config :as config]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.job.state.errors :as errors]
            [com.yetanalytics.xapipe.metrics :as metrics]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.spec.common :as cspec]
            [com.yetanalytics.xapipe.util.time :as t]
            [com.yetanalytics.xapipe.util.async :as ua]
            [com.yetanalytics.xapipe.xapi :as xapi])
  (:import [org.apache.http.impl.client CloseableHttpClient]))

(s/def ::job
  job/job-spec)

(s/def :com.yetanalytics.xapipe.stop-signal/status
  #{:paused
    :error})

(s/def ::stop-signal
  (s/keys :req-un [:com.yetanalytics.xapipe.stop-signal/status]
          :opt-un [::errors/error]))

(s/def ::states ::cspec/channel)
(s/def ::stop-fn (s/fspec :args (s/cat) :ret ::job))

(defn- post-loop
  [{init-state :state
    {{post-req-config   :request-config
      backoff-opts      :backoff-opts} :target} :config
    :as job}
   states-chan
   stop-chan
   batch-chan
   {:keys [conn-mgr
           http-client]
    :as conn-opts}
   reporter]
  (a/go
    (loop [state init-state]
      ;; Emit States
      (a/>! states-chan (assoc job :state state))
      ;; add any errors and flush the metrics before continue
      (let [[job-errors
             source-errors
             target-errors] (state/get-errors state)]
        (doto reporter
          (metrics/counter :xapipe/job-errors (count job-errors))
          (metrics/counter :xapipe/source-errors (count source-errors))
          (metrics/counter :xapipe/target-errors (count target-errors))
          (metrics/counter :xapipe/all-errors (count (concat
                                                      job-errors
                                                      source-errors
                                                      target-errors)))
          metrics/flush!))
      (cond
        (state/errors? state)
        (log/error "POST loop stopping with errors")

        (not= :running (:status state))
        (log/error "Stopping")

        :else
        (do
          (log/debug "POST loop run")
          (let [[v p] (a/alts! [stop-chan batch-chan] :priority true)]
            (if (= p stop-chan)
              (let [_ (log/debug "stop called...")
                    {:keys [status
                            error]} v]
                ;; A stop is called!
                (case status
                  :paused
                  (recur (state/set-status state :paused))
                  :error
                  (recur (state/add-error state error))))
              (if-some [{:keys [batch
                                filter-state]
                         :or {filter-state {}}} v]
                (let [_ (log/debugf "%d statement batch for POST" (count batch))
                      statements (mapv :statement batch)
                      cursor (-> statements last (get "stored") t/normalize-stamp)
                      _ (log/debugf "Cursor: %s" cursor)
                      attachments (mapcat :attachments batch)

                      _ (log/debugf "POSTing %d statements and %d attachments"
                                    (count statements)
                                    (count attachments))
                      ;; Form a post request

                      post-request (merge
                                    (client/post-request
                                     post-req-config
                                     statements
                                     attachments)
                                    ;; Use the conn + client
                                    conn-opts)
                      [tag x] (a/<! (client/async-request
                                     post-request
                                     :backoff-opts backoff-opts))]
                  (do
                    (a/<! (a/thread (mm/clean-tempfiles! attachments)))
                    (case tag
                      ;; On success, update the cursor and keep listening
                      :response
                      (let [{:keys [request-time]} x]
                        (doto reporter
                          (metrics/gauge
                           :xapipe/target-request-time
                           request-time)
                          (metrics/counter
                           :xapipe/statements
                           (count statements))
                          (metrics/counter
                           :xapipe/attachments
                           (count attachments)))
                        (recur (-> state
                                   (state/update-cursor cursor)
                                   (state/update-filter filter-state))))
                      ;; If the post fails, Send the error to the stop channel
                      ;; emit and stop.
                      :exception
                      (do
                        (log/errorf x "POST Exception: %s %s" (ex-message x)
                                    (some-> x
                                            ex-data
                                            :body))
                        (let [error {:message (ex-message x)
                                     :type    :target}]
                          (a/>! stop-chan {:status :error
                                           :error error})
                          (log/error "Stopping on POST error")
                          (recur (state/add-error state error)))))))
                ;; Job finishes
                ;; Might still be from pause/stop
                (if-some [stop-data (a/poll! stop-chan)]
                  ;; If so, recur to exit with that
                  (do
                    (log/debug "Detected stop after POST." stop-data)
                    (recur state))
                  ;; Otherwise we are complete!
                  (do
                    (log/info "Successful Completion")
                    (recur (state/set-status state :complete))))))))))
    ;; Post-loop, kill the HTTP client and close the states chan
    (client/shutdown conn-mgr)
    (.close ^CloseableHttpClient http-client)
    (a/close! states-chan)))

(s/def ::source-client-opts ::client/http-client-opts)
(s/def ::target-client-opts ::client/http-client-opts)

(s/def ::client-opts
  (s/keys :opt-un [::client/conn-mgr
                   ::client/http-client
                   ::client/conn-mgr-opts
                   ::source-client-opts
                   ::target-client-opts]))

(s/fdef run-job
  :args (s/cat :job ::job
               :kwargs (s/keys* :opt-un [::client-opts
                                         ::metrics/reporter]))
  :ret (s/keys :req-un [::states ::stop-fn]))

(defn run-job
  "Run a job, returning a map containing:
  :stop-fn - a function that will stop/pause it.
  :states - a channel of job states terminating in status :complete, :paused or
            :error.

  Note that the states channel is unbuffered, so you will need to consume it in
  order for job processing to continue."
  [job
   & {{:keys [conn-mgr
              http-client
              conn-mgr-opts
              source-client-opts
              target-client-opts]
       :or {conn-mgr-opts {}
            source-client-opts {}
            target-client-opts {}
            }} :client-opts
      reporter :reporter
      :or {reporter (metrics/->NoopReporter)}}]
  (let [{:keys [id]
         {status-before :status
          cursor-before :cursor
          filter-before :filter
          :as           state-before}      :state
         {{:keys [poll-interval]
           {?query-since :since
            :as          get-params}    :get-params
           get-req-config      :request-config
           source-backoff-opts :backoff-opts
           :as                 source-config} :source
          {target-batch-size   :batch-size
           post-req-config     :request-config
           target-backoff-opts :backoff-opts
           :as                 target-config} :target
          filter-config :filter
          :keys [get-buffer-size
                 statement-buffer-size
                 batch-buffer-size
                 batch-timeout]} :config
         :as                     job-before}

        (update job :config config/ensure-defaults)]
    (case status-before
      :running  (throw (ex-info "Job already running!"
                                {:type ::already-running
                                 :job  job-before}))
      :error    (throw (ex-info "Cannot start a job with errors"
                                {:type ::cannot-start-with-errors
                                 :job  job-before}))
      :complete (throw (ex-info "Cannot start a completed job"
                                {:type ::cannot-start-completed
                                 :job  job-before}))
      (let [;; Http async conn pool + client
            conn-mgr (or conn-mgr
                         (client/init-conn-mgr
                          conn-mgr-opts))
            source-client (or http-client
                              (client/init-client
                               conn-mgr source-client-opts))
            target-client (or http-client
                              (client/init-client
                               conn-mgr target-client-opts))
            ;; set up channels and start
            states-chan (a/chan)
            stop-chan (a/promise-chan)
            stop-fn   #(a/put! stop-chan {:status :paused})

            ;; Derive a since point for the query
            get-since (if ?query-since
                        (last (sort [cursor-before
                                     ?query-since]))
                        cursor-before)
            ;; A channel that will produce get responses
            get-chan (client/get-chan
                      (a/chan
                       get-buffer-size)
                      stop-chan
                      get-req-config
                      (assoc get-params :since get-since)
                      poll-interval
                      ;; kwargs
                      :backoff-opts
                      source-backoff-opts
                      :conn-opts
                      {:conn-mgr conn-mgr
                       :http-client source-client}
                      :reporter reporter)
            ;; A channel that holds statements + attachments
            statement-chan
            (a/chan statement-buffer-size)

            ;; Pipeline responses to statement chan, short circuiting errs
            _ (a/pipeline-blocking
               1
               statement-chan
               (comp
                ;; handle error resps
                (map (fn [[tag x]]
                       (case tag
                         :response x
                         :exception
                         (throw (ex-info
                                 (format "Source Error: %s"
                                         (ex-message x))
                                 {:type ::source}
                                 x)))))
                ;; coerce resp to statements
                (mapcat xapi/response->statements))
               get-chan
               true
               (fn [ex]
                 (a/put! stop-chan
                         {:status :error
                          :error {:message (ex-message ex)
                                  :type (case (some-> ex ex-data :type)
                                          ::source :source
                                          :job)}})
                 nil))
            ;; A channel that will get batches
            ;; NOTE: Apply other filtering here
            batch-chan
            (ua/batch-filter
             statement-chan
             (a/chan batch-buffer-size)
             target-batch-size
             batch-timeout
             :stateless-predicates
             (filt/stateless-predicates filter-config)
             :stateful-predicates
             (filt/stateful-predicates filter-config)
             :init-states filter-before
             :cleanup-fn
             (fn [{:keys [attachments]}]
               (when (not-empty attachments)
                 (a/thread (mm/clean-tempfiles! attachments))))
             :reporter reporter)
            ;; Send the init state
            _ (a/put! states-chan job-before)
            ;; Then set it as running for post
            running-state (state/set-status state-before :running)]
        ;; Post loop transfers statements until it reaches until or an error
        (post-loop
         (merge job-before
                {:state running-state})
         states-chan
         stop-chan
         batch-chan
         {:conn-mgr conn-mgr
          :http-client target-client}
         reporter)
        ;; Return the state emitter and stop fn
        {:states states-chan
         :stop-fn stop-fn}))))

(s/fdef log-states
  :args (s/cat
         :states ::cspec/channel
         :level #{:info :debug :trace :error :warn})
  :ret ::cspec/channel)

(defn log-states
  "Log a sequence of job states at the given level"
  [states
   level]
  (let [states-out (a/chan)]
    (a/go-loop []
      (if-let [{:keys [id state]
                :as job} (a/<! states)]
        (do
          (log/logf level
                    "Job ID %s state: %s"
                    id state)
          (a/>! states-out job)
          (recur))
        (a/close! states-out)))
    states-out))

(s/fdef store-states
  :args (s/cat :states ::states ;; successive job maps
               :store #(satisfies? store/XapipeStore %)
               :kwargs (s/keys* :opt-un [::metrics/reporter]))
  :ret ::cspec/channel) ;; a channel with final state

(defn store-states
  "Write states to storage, which is assumed to be a blocking operation.
  Return a final job state, possibly decorated with a job persistence error."
  [states
   store
   & {:keys [reporter]
      :or {reporter (metrics/->NoopReporter)}}]
  (a/go-loop [last-job nil]
    (log/debug "storage loop run")
    (if-let [{{:keys [status]
               :as state} :state
              :as job} (a/<! states)]
      (let [[tag x] (a/<!
                     (a/thread
                       (try
                         (if (store/write-job store
                                              job)
                           [:result true]
                           [:exception
                            (ex-info "Unknown storage write error"
                                     {:type ::unknown-storage-error})])
                         (catch Throwable ex
                           [:exception (ex-info "Storage write error"
                                                {:type ::storage-error}
                                                ex)]))))]
        (case tag
          :result
          (do
            (log/debug "state stored")
            (recur job))
          :exception
          (do
            (log/error x "State storage error, closing")
            (update job :state
                    state/add-error {:type :job
                                     :message (ex-message x)}))))
      last-job)))

(comment
  (require '[com.yetanalytics.xapipe.store.impl.memory :as mem])

  (def store (mem/new-store))

  (def job-id (str (java.util.UUID/randomUUID)))

  (let [job (job/init-job
             job-id
             {:source
              {:request-config {:url-base    "http://localhost:8080"
                                :xapi-prefix "/xapi"}
               :get-params     {}
               :poll-interval  1000
               :batch-size     50}
              :target
              {:request-config {:url-base    "http://localhost:8081"
                                :xapi-prefix "/xapi"}
               :batch-size     50}})


        {:keys [states]
         stop :stop-fn} (run-job job)

        store-result
        (-> states
            (log-states :info)
            (store-states store))]
    (a/go
      (let [result (a/<! store-result)]
        (log/infof "store result: %s" result)))
    (def stop-fn stop))

  (clojure.pprint/pprint (stop-fn))

  )

(comment
  ;; Same as above, but with redis as a store
  (require '[com.yetanalytics.xapipe.store.impl.redis :as redis])

  (def store (redis/new-store {:pool {}
                               :spec {:uri "redis://localhost:6379"}}))

  (def job-id (str (java.util.UUID/randomUUID)))

  (let [job (job/init-job
             job-id
             {:source
              {:request-config {:url-base    "http://localhost:8080"
                                :xapi-prefix "/xapi"}
               :get-params     {}
               :poll-interval  1000
               :batch-size     50}
              :target
              {:request-config {:url-base    "http://localhost:8081"
                                :xapi-prefix "/xapi"}
               :batch-size     50}})


        {:keys [states]
         stop :stop-fn} (run-job job)

        store-result
        (-> states
            (log-states :info)
            (store-states store))]
    (a/go
      (let [result (a/<! store-result)]
        (log/infof "store result: %s" result)))
    (def stop-fn stop))

  (clojure.pprint/pprint (stop-fn))

  (require '[taoensso.carmine :as car])

  (let [conn {:pool {}
              :spec {:uri "redis://localhost:6379"}}]
    (car/wcar conn
              (car/get job-id)))


  ;; Resume
  (let [job (store/read-job store job-id)

        {:keys [states]
         stop :stop-fn} (run-job job)

        store-result
        (-> states
            (log-states :info)
            (store-states store))]
    (a/go
      (let [result (a/<! store-result)]
        (log/infof "store result: %s" result)))
    (def stop-fn stop))
  (stop-fn)
  )

(ns com.yetanalytics.xapipe
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.event :as event]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.state.errors :as errors]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.util.time :as t]
            [com.yetanalytics.xapipe.xapi :as xapi]))

(s/def ::job
  job/job-spec)

(s/def ::event
  event/event-spec)

(s/def :com.yetanalytics.xapipe.stop-signal/status
  #{:paused
    :error})

(s/def ::stop-signal
  (s/keys :req-un [:com.yetanalytics.xapipe.stop-signal/status]
          :opt-un [::errors/error]))

(s/fdef run-job
  :args (s/cat :store #(satisfies? store/XapipeStore %)
               :id ::job/id
               :init-config ::job/config)
  :ret (s/fspec :args (s/cat) :ret ::job))

(defn run-job
  "Run a job, returning a function that will stop/pause it."
  [store
   id
   init-config]
  (let [{{status-before :status
          cursor-before :cursor} :state
         :as                     job-before}
        (store/init-job store id init-config)]
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
      ;; set up channels and start
      (let [stop-chan (a/promise-chan)
            stop-fn   #(a/put! stop-chan {:status :paused})
            {{{:keys [poll-interval]
               {?query-since :since
                :as get-params} :get-params
               get-req-config :request-config
               :as            source-config} :source
              {target-batch-size :batch-size
               post-req-config   :request-config
               :as               target-config} :target} :config} job-before
            ;; Derive a since point for the query
            get-since (if ?query-since
                        (t/latest-stamp cursor-before
                                        ?query-since)
                        cursor-before)
            get-chan (client/get-chan
                      (a/chan
                       100 ;; TODO: configurable buffer
                       (comp
                        (map (fn [[tag x]]
                               (case tag
                                 :response x
                                 :exception (throw x))))
                        (mapcat xapi/response->statements)
                        (partition-all target-batch-size))
                       (fn [ex]
                         (a/put! stop-chan {:status :error
                                            :error {:message (ex-message ex)
                                                    :type :source}})
                         nil))
                      stop-chan
                      get-req-config
                      (assoc get-params :since get-since)
                      poll-interval)]
        ;; Set as running
        (store/update-job store id nil [] nil)
        ;; Post loop
        (a/go-loop []
          (println "post loop run")
          (if-let [{:keys [status
                           error]} (a/poll! stop-chan)]
            ;; A stop is called!
            (case status
              :paused (store/update-job store id nil [] :paused)
              :error (store/update-job store id nil [error] nil))
            (if-let [batch (a/<! get-chan)]
              (let [statements (mapv :statement batch)
                    cursor (-> statements last (get "stored"))
                    attachments (mapcat :attachments batch)]
                (println (format "batch cursor %s" cursor))
                (do (println (format "POSTing %d statements and %d attachments"
                                     (count statements)
                                     (count attachments)))
                    (store/update-job store id cursor [] nil)
                    (recur))
                #_(let [_ (println (format "POSTing %d statements"
                                         (count statements)))
                      post-request (client/post-request
                                    post-req-config
                                    (mapv :statement statements)
                                    (into [] (mapcat :attachments statements)))
                      [tag x] (a/<! (client/async-request post-request))]
                  (case tag
                    ;; On success, update the cursor and keep listening
                    :response
                    (do (store/update-job store id cursor [] nil)
                        (recur))
                    ;; If the post fails, log the error and stop
                    :exception
                    (store/update-job
                     store id nil
                     [{:message (ex-message x)
                       :type    :target}]
                     nil))))
              ;; Job finishes naturally
              (do (println "job finish")
                  (store/update-job store id nil [] :complete)))))
        ;; Return the stop function
        stop-fn))))


(comment

  (require '[com.yetanalytics.xapipe.store.impl.memory :as mem])

  (def store (mem/new-store))

  (def job-id (str (java.util.UUID/randomUUID)))
  job-id
  (def stop-fn
    (run-job store
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
               :batch-size     50}}))


  (clojure.pprint/pprint (stop-fn))
  (clojure.pprint/pprint (store/get-job store job-id))

  ;; Last stored time
  "2021-10-18T16:22:09.866071000Z"


  (let [stop-chan (a/promise-chan)]
    (a/<!!
     (a/into []
             (a/take 10
                     (client/get-chan
                      (a/chan
                       1 ;; TODO: configurable buffer
                       (map event/get->event))
                      stop-chan
                      {:url-base    "http://localhost:8080"
                       :xapi-prefix "/xapi"}
                      {}
                      1000)))))
  )

;; TODO: delete boiler
(defn greet
  "Callable entry point to the application."
  [data]
  (println (str "Hello, " (or (:name data) "World") "!")))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (greet {:name (first args)}))

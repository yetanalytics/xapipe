(ns com.yetanalytics.xapipe
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]
            [clojure.core.async :as a]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.xapi :as xapi]))

(s/def ::job
  job/job-spec)

;; Dev Job state, just an atom we write to for now
(defonce job-state
  (atom {}))

(s/fdef run-job
  :args (s/cat :id ::job/id :config ::job/config)
  :ret (s/fspec :args (s/cat) :ret ::job)) ;; stop-fn

(defn run-job
  "Run a job, returning a function that will stop/pause it."
  [id
   {init-source-config :source
    init-target-config :target}]
  (let [{{status-before :status} :state
         :as                     job-before}
        (get
         (swap! job-state
                (fn [s]
                  (if (get s id)
                    s
                    (assoc s id (job/init-job
                                 id
                                 init-source-config
                                 init-target-config)))))
         id)]
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
      (let [stop-chan (a/chan)
            stop-fn   #(do (a/>!! stop-chan true)
                           (get @job-state id))
            {{{:keys [poll-interval
                      get-params]
               get-req-config :request-config
               :as   source-config} :source
              {target-batch-size :batch-size
               post-req-config :request-config
               :as               target-config}   :target} :config
             :as      started-job}
            (get (swap! job-state update id job/set-status :running) id)]
        (if (= :running (job/get-status started-job))
          (let [get-chan       (client/get-chan
                                (a/chan) ;; TODO: buffer
                                stop-chan
                                get-req-config
                                get-params
                                poll-interval)
                statement-chan (a/chan (:limit get-params)
                                       (comp
                                        (mapcat xapi/response->statements)
                                        (partition-all target-batch-size)))]
            ;; Handle gets
            (a/go-loop []
              (if-let [[tag x] (a/<! get-chan)]
                (case tag
                  :response
                  (do (a/>! statement-chan x)
                      (recur))
                  :exception
                  (swap! job-state
                         update id job/add-error :source {:message (ex-message x)}))
                (a/close! statement-chan)))
            ;; Handle posts
            (a/go-loop []
              (if-let [batch   (a/<! statement-chan)]
                (let [[tag x] (a/<! (client/async-request
                                     (client/post-request
                                      post-req-config
                                      (mapv :statement batch)
                                      (into [] (mapcat :attachments batch)))))]
                  (case tag
                    :response
                    (let [cursor (-> batch last :statement (get "stored"))]
                      (swap! job-state
                             update
                             id
                             job/update-cursor
                             cursor)
                      (recur))
                    :exception
                    (swap! job-state
                           update
                           id
                           job/add-error :target {:message (ex-message x)})))
                ;; On completion, try to write
                (swap! job-state update id job/set-status :completed))))
          (throw (ex-info "Could not start job"
                          {:type ::cannot-start-unknown
                           :job  started-job})))
        stop-fn)))

  )


(comment
  (reset! job-state {})
  (clojure.pprint/pprint @job-state)

  (def stop-fn
    (run-job (str (java.util.UUID/randomUUID))
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
  (stop-fn)

  (job/set-status {"78228fb1-6fe8-4deb-8ee8-75584d2e4b7c"
                   {:id "78228fb1-6fe8-4deb-8ee8-75584d2e4b7c",
                    :config
                    {:source
                     {:request-config
                      {:url-base "http://localhost:8080", :xapi-prefix "/xapi"},
                      :get-params {:limit 50},
                      :poll-interval 1000,
                      :batch-size 50},
                     :target
                     {:request-config
                      {:url-base "http://localhost:8081", :xapi-prefix "/xapi"},
                      :batch-size 50}},
                    :state
                    {:status :init,
                     :cursor "1970-01-01T00:00:00Z",
                     :source {:errors []},
                     :target {:errors []},
                     :errors []}}}
                  :running)



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

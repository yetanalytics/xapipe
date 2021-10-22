(ns com.yetanalytics.xapipe
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.client.multipart-mixed :as mm]
            [com.yetanalytics.xapipe.event :as event]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.state.errors :as errors]
            [com.yetanalytics.xapipe.store :as store]
            [com.yetanalytics.xapipe.util.time :as t]
            [com.yetanalytics.xapipe.util.async :as ua]
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
               :as               target-config} :target
              :keys [get-buffer-size
                     statement-buffer-size
                     get-proc-conc
                     batch-buffer-size
                     batch-timeout]} :config} job-before
            ;; Derive a since point for the query
            get-since (if ?query-since
                        (t/latest-stamp cursor-before
                                        ?query-since)
                        cursor-before)
            ;; A channel that will produce get responses
            get-chan (client/get-chan
                      (a/chan
                       get-buffer-size)
                      stop-chan
                      get-req-config
                      (assoc get-params :since get-since)
                      poll-interval)
            ;; A channel that holds statements + attachments
            statement-chan (a/chan statement-buffer-size)

            ;; Pipeline responses to statement chan, short circuiting errs
            _ (a/pipeline-blocking
               get-proc-conc
               statement-chan
               (comp
                (map (fn [[tag x]]
                       (case tag
                         :response x
                         :exception (throw x))))
                (mapcat xapi/response->statements))
               get-chan
               true
               (fn [ex]
                 (a/put! stop-chan {:status :error
                                    :error {:message (ex-message ex)
                                            :type :source}})
                 nil))
            ;; A channel that will get batches
            ;; NOTE: Apply other filtering here
            batch-chan (let [c (a/chan batch-buffer-size)]
                         (ua/batch
                          statement-chan
                          c
                          target-batch-size
                          batch-timeout)
                         c)]
        ;; Post loop
        (a/go-loop []
          (log/debug "POST")
          (let [[v p] (a/alts! [stop-chan batch-chan])]
            (if (= p stop-chan)
              (let [_ (log/debug "stop called...")
                    {:keys [status
                            error]} v]
                ;; A stop is called!
                (case status
                  :paused
                  (do
                    (log/info "Pausing.")
                    (store/update-job store id nil [] :paused))
                  :error
                  (do
                    (log/errorf "Stopping with error: %s" (:message error))
                    (store/update-job store id nil [error] nil))))
              (if-some [batch v]
                (let [_ (log/debugf "%d statement batch for POST" (count batch))
                      statements (mapv :statement batch)
                      cursor (-> statements last (get "stored"))
                      _ (log/debugf "Cursor: %s" cursor)
                      attachments (mapcat :attachments batch)

                      _ (log/debugf "POSTing %d statements and %d attachments"
                                    (count statements)
                                    (count attachments))
                      ;; Form a post request

                      post-request (client/post-request
                                    post-req-config
                                    statements
                                    attachments)
                      [tag x] (a/<! (client/async-request post-request))]
                  (do
                    (case tag
                      ;; On success, update the cursor and keep listening
                      :response
                      (do (mm/clean-tempfiles! attachments)
                          (store/update-job store id cursor [] nil)
                          (recur))
                      ;; If the post fails, Send the error to the stop channel and
                      ;; recur to write and then bail
                      :exception
                      (do
                        (log/errorf x "POST Exception: %s %s" (ex-message x)
                                    (some-> x
                                            ex-data
                                            :body))
                        ;; Recreate and log req body to file
                        #_(-> (client/post-request
                             post-req-config
                             statements
                             attachments)
                            :body
                            (io/copy (io/file (format "failures/%s_%s_%s.request"
                                                      id
                                                      (-> statements first (get "stored"))
                                                      (-> statements last (get "stored"))))))
                        (mm/clean-tempfiles! attachments)
                        (a/>! stop-chan {:status :error
                                         :error {:message (ex-message x)
                                                 :type    :target}})
                        (recur)))))
                ;; Job finishes
                ;; Might still be from pause/stop
                (if-some [stop-data (a/poll! stop-chan)]
                  ;; If so, recur to exit with that
                  (do
                    (log/debug "Detected stop after POST." stop-data)
                    (recur))
                  ;; Otherwise we are complete!
                  (do
                    (log/info "Successful Completion")
                    (store/update-job store id nil [] :complete)))))))
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

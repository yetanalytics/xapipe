(ns com.yetanalytics.xapipe.main
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.cli :as cli]
            [com.yetanalytics.xapipe.cli.options :as opts]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.store :as store])
  (:gen-class))

(def usage
  "
Run a new job:
    --source-url http://0.0.0.0:8080/xapi --target-url http://0.0.0.0:8081/xapi

Resume a paused job:
    --job-id <id>

Force Resume a job with errors:
    --job-id <id> -f
")

(defn main*
  [& args]
  (let [{{help? :help
          ?job-id :job-id
          show-job? :show-job
          list-jobs? :list-jobs
          force-resume? :force-resume
          ?json :json
          ?json-file :json-file
          :as options} :options
         :keys [summary]} (opts/args->options args)
        ]
    (if help?
      {:status 0
       :message (str
                 usage
                 "All options:\n"
                 summary)}
      (let [store (cli/create-store options)]
        (if list-jobs?
          (do
            (cli/list-store-jobs store)
            {:status 0})
          (let [[new? job'] (or
                             (and ?json [true ?json])
                             (and ?json-file [true ?json-file])
                             (if-some [extant (and ?job-id
                                                   (store/read-job store ?job-id))]
                               [false extant]
                               [true (cli/create-job
                                      options)]))
                job (cond-> job'
                      (not new?)
                      (->
                       (cli/reconfigure-job options)
                       (cond->
                           force-resume?
                         (-> (update :state state/clear-errors)
                             (update :state state/set-status :paused)))))]
            (if (s/valid? job/job-spec job)
              (do
                (if new?
                  (log/infof "Created new job %s: %s" (:id job) (pr-str job))
                  (log/infof "Found existing job %s: %s" (:id job) (pr-str job)))
                (if show-job?
                  {:status 0
                   :message (pr-str job)}
                  (do
                    (log/infof
                     (if new?
                       "Starting job %s"
                       "Resuming job %s")
                     (:id job))
                    (cli/handle-job store
                                    job
                                    (cli/options->client-opts options)))))
              {:status 1
               :message (s/explain-str job/job-spec job)})))))))

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

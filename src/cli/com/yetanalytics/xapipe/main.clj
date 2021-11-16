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

List All Jobs:
    --list-jobs

Delete a Job:
    --delete-job <id>

")

(defn main*
  [& args]
  (let [{{help? :help
          ?job-id :job-id
          show-job? :show-job
          list-jobs? :list-jobs
          ?delete-job-id :delete-job
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
        (cond
          ?delete-job-id
          (if (true? (store/delete-job store ?delete-job-id))
            {:status 0
             :message "Job Deleted"}
            {:status 1
             :message "Job Not Deleted"})

          list-jobs?
          (do
            (cli/list-store-jobs store)
            {:status 0})

          :else
          (let [[new? job'] (or
                             (and ?json [true ?json])
                             (and ?json-file [true ?json-file])
                             (if-some [extant (and ?job-id
                                                   (store/read-job store ?job-id))]
                               [false extant]
                               [true (cli/create-job
                                      options)]))
                {job-id :id
                 :as job} (cond-> job'
                            (not new?)
                            (->
                             (cli/reconfigure-job options)
                             (cond->
                                 force-resume?
                               (-> (update :state state/clear-errors)
                                   (update :state state/set-status :paused)))))
                reporter (cli/create-reporter job-id options)]
            (if (s/valid? job/job-spec job)
              (do
                (if new?
                  (log/infof
                   "Created new job %s: %s"
                   job-id
                   (pr-str
                    (job/sanitize job)))
                  (log/infof
                   "Found existing job %s: %s"
                   job-id
                   (pr-str
                    (job/sanitize job))))
                (if show-job?
                  {:status 0
                   :message (pr-str
                             (job/sanitize job))}
                  (do
                    (log/infof
                     (if new?
                       "Starting job %s"
                       "Resuming job %s")
                     job-id)
                    (cli/handle-job store
                                    job
                                    (cli/options->client-opts options)
                                    reporter))))
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

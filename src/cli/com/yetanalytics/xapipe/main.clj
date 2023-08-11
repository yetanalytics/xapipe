(ns com.yetanalytics.xapipe.main
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.cli :as cli]
            [com.yetanalytics.xapipe.cli.options :as opts]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.json :as jj]
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
  (try
    (let [{{help?          :help
            ?job-id-arg    :job-id
            show-job?      :show-job
            list-jobs?     :list-jobs
            ?delete-job-id :delete-job
            force-resume?  :force-resume
            ?json          :json
            ?json-file     :json-file
            ?json-out      :json-out
            :as            options} :options
           :keys         [summary]} (opts/args->options args)]
      (if help?
        {:status  0
         :message (str
                   usage
                   "All options:\n"
                   summary)}
        (let [store (cli/create-store options)]
          (cond
            ?delete-job-id
            (if (true? (store/delete-job store ?delete-job-id))
              {:status  0
               :message "Job Deleted"}
              {:status  1
               :message "Job Not Deleted"})

            list-jobs?
            (do
              (cli/list-store-jobs store)
              {:status 0})

            :else
            (let [?from-json (or ?json ?json-file)
                  _ (when ?from-json
                      ;; If job-id arg is set, we make sure they match
                      (when (and ?job-id-arg
                                 (not= ?job-id-arg
                                       (:id ?from-json)))
                        (throw (ex-info (format "--job-id %s does not match JSON job id %s"
                                                ?job-id-arg (:id ?from-json))))))
                  ?job-id (or ?job-id-arg (:id ?from-json))
                  ?from-storage (and ?job-id
                                     (store/read-job
                                      store ?job-id))


                  {job-id :id
                   :as    job}
                  (cond
                    ;; Found in storage
                    ?from-storage
                    (-> ?from-storage
                        job/upgrade-job
                        (cond->
                          ;; If the user has requested force resume we clear
                         force-resume?
                          (-> (update :state state/clear-errors)
                              (update :state state/set-status :paused)))
                        (job/reconfigure-job
                         (cli/reconfigure-with-options
                          (:config (or ?from-json ?from-storage))
                          ;; reparse the args w/o defaults
                          (:options
                           (opts/args->options args
                                               :no-defaults true)))))
                    ;; Json is provided
                    ?from-json
                    (-> ?from-json
                        job/upgrade-job
                        (update :config cli/reconfigure-with-options
                                ;; reparse args w/o defaults
                                (:options
                                 (opts/args->options args
                                                     :no-defaults true))))

                    ;; New from options!
                    :else
                    (cli/create-job options))
                  reporter    (cli/create-reporter job-id options)
                  new? (not (some? ?from-storage))]

              (if new?
                (log/infof
                 "Created new job %s: %s"
                 job-id
                 (jj/job->json
                  (job/sanitize job)))
                (log/infof
                 "Found existing job %s: %s"
                 job-id
                 (jj/job->json
                  (job/sanitize job))))

              (cond
                ;; Check job for validity!
                (not (s/valid? job/job-spec job))
                {:status  1
                 :message (s/explain-str job/job-spec (job/sanitize job))}

                ;; Check job for Errors!
                (job/errors? job)
                {:status 1
                 :message (cli/errors->message (job/all-errors job))}

                show-job?   {:status  0
                             :message (jj/job->json
                                       (job/sanitize job))}
                (not-empty
                 ?json-out) (do
                              (jj/job->json-file! job ?json-out)
                              {:status  0
                               :message (format "Wrote job %s to %s"
                                                job-id ?json-out)})
                :else (do

                        (log/infof
                         (if new?
                           "Starting job %s"
                           "Resuming job %s")
                         job-id)
                        (cli/handle-job store
                                        job
                                        (cli/options->client-opts options)
                                        reporter))))))))
    (catch Exception ex
      {:status  1
       :message (ex-message ex)})))

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

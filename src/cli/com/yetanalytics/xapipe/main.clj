(ns com.yetanalytics.xapipe.main
  (:require [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.cli :as cli]
            [com.yetanalytics.xapipe.cli.options :as opts]
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
          force-resume? :force-resume
          :as options} :options
         :keys [summary]} (opts/args->options args)
        ]
    (if help?
      {:status 0
       :message (str
                 usage
                 "All options:\n"
                 summary)}
      (let [store (cli/create-store options)
            [new? job] (if-some [extant (and ?job-id
                                             (store/read-job store ?job-id))]
                         [false extant]
                         [true (cli/create-job
                                options)])]
        (if new?
          (log/infof "Created new job %s: %s" (:id job) (pr-str job))
          (log/infof "Found existing job %s: %s" (:id job) (pr-str job)))
        (if show-job?
          {:exit 0
           :message (pr-str job)}
          (do
            (log/infof
             (if new?
               "Starting job %s"
               "Resuming job %s")
             (:id job))
            (cli/handle-job store
                            (cond-> (cli/reconfigure-job job options)
                              (and
                               (not new?)
                               force-resume?)
                              (-> (update :state state/clear-errors)
                                  (update :state state/set-status :paused)))
                            (cli/options->client-opts options))))))))

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

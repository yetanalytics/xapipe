(ns com.yetanalytics.xapipe.store
  "Persistent job state storage"
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.job :as job]))

(defprotocol XapipeStore
  (init-job [store job-id job-config]
    "Returns the job with job-id or uses the config to initialize it")
  (get-job [store job-id]
    "Return the job if available.")
  (update-job [store job-id ?cursor errors ?command]
    "Update the job's data"))

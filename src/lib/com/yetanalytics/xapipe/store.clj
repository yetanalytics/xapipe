(ns com.yetanalytics.xapipe.store
  "Persistent job state storage")

(defprotocol XapipeStore
  (read-job [store job-id]
    "Return the job if available, or nil")
  (write-job [store job]
    "Write a job to the store, overwriting previous state. Return true/false")
  (list-jobs [store]
    "Return a list of jobs"))

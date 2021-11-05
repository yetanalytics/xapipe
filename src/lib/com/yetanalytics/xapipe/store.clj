(ns com.yetanalytics.xapipe.store
  "Persistent job state storage"
  (:require [clojure.spec.alpha :as s]))

(defprotocol XapipeStore
  (read-job [store job-id]
    "Return the job if available, or nil")
  (write-job [store job]
    "Write a job to the store, overwriting previous state. Return true/false")
  (list-jobs [store]
    "Return a list of jobs")
  (delete-job [store job-id]
    "Delete the job from the store, returning true/false"))

(s/def :com.yetanalytics.xapipe/store
  #(satisfies? XapipeStore %))

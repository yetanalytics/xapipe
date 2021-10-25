(ns com.yetanalytics.xapipe.store.impl.noop
  "A stateless state store"
  (:require [com.yetanalytics.xapipe.store :as store]))

(deftype NoopStore []
  store/XapipeStore
  (read-job [_store _job-id]
    nil)
  (write-job [_store _job-id]
    true))

(defn new-store
  "Make a new noop store"
  [& _]
  (->NoopStore))

(ns com.yetanalytics.xapipe.store.impl.memory
  (:require [com.yetanalytics.xapipe.store :as store]))

(deftype MemoryStore [state-atom]
  store/XapipeStore
  (read-job [store job-id]
    (get @state-atom job-id))
  (write-job [store {job-id :id
                     :as job}]
    (-> (swap! state-atom assoc job-id job)
        (get job-id)
        (= job))))

(defn new-store
  "Make a new in-memory store"
  []
  (let [state-atom (atom {})]
    (->MemoryStore state-atom)))

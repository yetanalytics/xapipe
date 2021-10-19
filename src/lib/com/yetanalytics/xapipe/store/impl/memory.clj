(ns com.yetanalytics.xapipe.store.impl.memory
  (:require [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store :as store]))

(deftype MemoryStore [state-atom]
  store/XapipeStore
  (init-job [store job-id job-config]
    (-> state-atom
        (swap! (fn [store-state new-job]
                 (if-let [job (get store-state job-id)]
                   store-state
                   (assoc store-state
                          job-id
                          new-job)))
               (job/init-job job-id
                             (:source job-config)
                             (:target job-config)))
        (get job-id)))
  (get-job [store job-id]
    (get @state-atom job-id))
  (update-job [store job-id ?cursor errors ?command]
    (-> state-atom
        (swap! update job-id job/update-job ?cursor errors ?command)
        (get job-id))))

(defn new-store
  "Make a new in-memory store"
  []
  (let [state-atom (atom {})]
    (->MemoryStore state-atom)))

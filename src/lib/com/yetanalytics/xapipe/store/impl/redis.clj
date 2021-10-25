(ns com.yetanalytics.xapipe.store.impl.redis
  (:require [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.store :as store]
            [taoensso.carmine :as car]))

(deftype RedisStore [conn]
  store/XapipeStore
  (read-job [store job-id]
    (car/wcar conn
              (car/get job-id)))
  (write-job [store {job-id :id
                     :as job}]
    (let [[stat ret-job] (car/wcar conn
                                   (car/set job-id job)
                                   (car/get job-id))]
      (if (= "OK" stat)
        (= job ret-job)
        (do
          (log/errorf stat "Redis Exception")
          false)))))

(defn new-store
  "Make a new redis store"
  [conn]
  (->RedisStore conn))

(comment
  (def conn
    {:pool {}
     :spec {:uri "redis://localhost:6379"}})

  (car/wcar conn
            (car/set "foo" {:bar "baz"})
            (car/get "foo"))

  )

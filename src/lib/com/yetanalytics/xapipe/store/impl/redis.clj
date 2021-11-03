(ns com.yetanalytics.xapipe.store.impl.redis
  (:require [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.store :as store]
            [taoensso.carmine :as car]))

(deftype RedisStore [conn
                     prefix]
  store/XapipeStore
  (read-job [store job-id]
    (car/wcar conn
              (car/get (format "%s:%s" prefix job-id))))
  (write-job [store {job-id :id
                     :as job}]
    (let [k (format "%s:%s" prefix job-id)
          [stat ret-job] (car/wcar conn
                                   (car/set k job)
                                   (car/get k))]
      (if (= "OK" stat)
        (= job ret-job)
        (do
          (log/errorf stat "Redis Exception")
          false)))))

(defn new-store
  "Make a new redis store"
  [conn & [?prefix]]
  (->RedisStore conn
                (or ?prefix
                    "xapipe")))

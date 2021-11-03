(ns com.yetanalytics.xapipe.store.impl.redis
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.store :as store]
            [taoensso.carmine :as car]))

(s/fdef scan-seq
  :args (s/cat :conn map?
               :prefix string?
               :cursor string?)
  :ret (s/every job/job-spec))

(defn- scan-seg!
  [conn prefix cursor]
  (let [[next-cursor ks]
        (car/wcar conn
                  (car/scan cursor :match (format "%s:*"
                                                  prefix)))]
    (when (not-empty ks)
      [next-cursor
       (car/wcar conn
                 (apply car/mget ks))])))

(defn scan-seq
  "Return a (blocking) lazy seq of jobs in redis"
  ([conn prefix]
   (scan-seq conn prefix "0"))
  ([conn prefix cursor]
   (lazy-seq
    (let [[next-cursor jobs] (scan-seg! conn prefix (or cursor
                                                        "0"))]
      (concat jobs
              (when (not= next-cursor "0")
                (scan-seq conn prefix next-cursor)))))))

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
          false))))
  (list-jobs [store]
    (scan-seq conn prefix)))

(defn new-store
  "Make a new redis store"
  [conn & [?prefix]]
  (->RedisStore conn
                (or ?prefix
                    "xapipe")))

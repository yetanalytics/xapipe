(ns com.yetanalytics.xapipe.job.config
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]))

(s/def ::batch-size pos-int?) ;; Will be used to supply limit

(s/def ::source
  (s/keys :req-un [::client/request-config
                   ::client/poll-interval
                   ::batch-size]
          :opt-un [::client/get-params]))

(s/def ::target
  (s/keys :req-un [::client/request-config
                   ::batch-size]))

(def config-spec
  (s/keys :req-un [::source
                   ::target]))

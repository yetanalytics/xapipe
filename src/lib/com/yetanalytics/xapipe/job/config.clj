(ns com.yetanalytics.xapipe.job.config
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]))

(s/def ::batch-size pos-int?) ;; limit param for get, batch size for post

(s/def ::source
  (s/keys :req-un [::client/request-config
                   ::client/poll-interval
                   ::batch-size]
          :opt-un [::client/get-params]))

(s/def ::target
  (s/keys :req-un [::client/request-config
                   ::batch-size]))

;; How many get requests to read-ahead
;; default: 10
(s/def ::get-buffer-size pos-int?)

;; How many statements to buffer
;; default: source batch-size * get-buffer-size
(s/def ::statement-buffer-size pos-int?)

;; Desired thread conc of response processing
(s/def ::get-proc-conc pos-int?)

;; how many batches of (target batch size) to buffer
;; default: statement-buffer-size / target batch-size
(s/def ::batch-buffer-size pos-int?)

;; How long will we wait for a batch to fill?
(s/def ::batch-timeout pos-int?)

(def config-spec
  (s/keys :req-un [::source
                   ::target]
          :opt-un [::get-buffer-size
                   ::statement-buffer-size
                   ::get-proc-conc
                   ::batch-buffer-size
                   ::batch-timeout]))

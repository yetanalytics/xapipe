(ns com.yetanalytics.xapipe.job.config
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.filter :as filt]
            [com.yetanalytics.xapipe.util :as u]))

(s/def ::batch-size pos-int?) ;; limit param for get, batch size for post

(s/def ::backoff-opts u/backoff-opts-spec)

(s/def ::source
  (s/keys :req-un [::client/request-config]
          :opt-un [::client/get-params
                   ::client/poll-interval
                   ::batch-size
                   ::backoff-opts]))

(s/def ::target
  (s/keys :req-un [::client/request-config]
          :opt-un [::batch-size
                   ::backoff-opts]))

;; How many get requests to read-ahead
;; default: 10
(s/def ::get-buffer-size pos-int?)

;; How many statements to buffer
;; default: source batch-size * get-buffer-size
(s/def ::statement-buffer-size pos-int?)

;; how many batches of (target batch size) to buffer
;; default: statement-buffer-size / target batch-size
(s/def ::batch-buffer-size pos-int?)

;; How long will we wait for a batch to fill?
(s/def ::batch-timeout pos-int?)

;; Filter config
(s/def ::filter filt/filter-config-spec)

(def config-spec
  (s/keys :req-un [::source
                   ::target]
          :opt-un [::get-buffer-size
                   ::statement-buffer-size
                   ::batch-buffer-size
                   ::batch-timeout
                   ::filter]))

;; ensure a config has optional keys w/defaults
(s/fdef ensure-defaults
  :args (s/cat :config config-spec)
  :ret config-spec)

(defn ensure-defaults
  "Apply configuration defaults"
  [{{get-batch-size   :batch-size
     get-backoff-opts :backoff-opts
     poll-interval    :poll-interval
     :as              source-config
     :or              {get-batch-size   50
                       get-backoff-opts {:budget      10000
                                         :max-attempt 10}
                       poll-interval    1000}}
    :source
    {post-batch-size   :batch-size
     post-backoff-opts :backoff-opts
     :as               target-config
     :or               {post-backoff-opts {:budget      10000
                                           :max-attempt 10}}}
    :target
    filter-config :filter
    :keys
    [get-buffer-size
     statement-buffer-size
     batch-buffer-size
     batch-timeout]
    :or
    {get-buffer-size 10
     batch-timeout   200}}]
  (let [post-batch-size
        (or post-batch-size
            get-batch-size)

        statement-buffer-size
        (or statement-buffer-size
            (* get-batch-size
               get-buffer-size))

        batch-buffer-size
        (or batch-buffer-size
            (quot statement-buffer-size
                  post-batch-size))]
    {:get-buffer-size       get-buffer-size
     :statement-buffer-size statement-buffer-size
     :batch-buffer-size     batch-buffer-size
     :batch-timeout         batch-timeout
     :source
     (-> source-config
         (assoc :batch-size get-batch-size
                :backoff-opts get-backoff-opts
                :poll-interval poll-interval)
         (assoc-in [:get-params :limit] get-batch-size))
     :target
     (assoc target-config
            :batch-size post-batch-size
            :backoff-opts post-backoff-opts)
     :filter
     (or filter-config {})}))

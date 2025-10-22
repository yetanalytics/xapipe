(ns com.yetanalytics.xapipe.job.config
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.filter :as filt]
            [com.yetanalytics.xapipe.util :as u]
            [com.yetanalytics.xapipe.util.time :as t]))

;; TODO: The generated values here blow things up on large tests
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
(s/def ::get-buffer-size nat-int?)

;; How many statements to buffer
;; default: source batch-size * get-buffer-size
(s/def ::statement-buffer-size nat-int?)

;; how many batches of (target batch size) to buffer
;; default: statement-buffer-size / target batch-size
(s/def ::batch-buffer-size nat-int?)

;; How long will we wait for a batch to fill?
(s/def ::batch-timeout pos-int?)

;; Buffer size for async tempfile cleanup
(s/def ::cleanup-buffer-size nat-int?)

;; Filter config
(s/def ::filter
  (s/with-gen filt/filter-config-spec
    (fn []
      (sgen/return {}))))

(def config-spec
  (s/keys :req-un [::source
                   ::target]
          :opt-un [::get-buffer-size
                   ::statement-buffer-size
                   ::batch-buffer-size
                   ::batch-timeout
                   ::cleanup-buffer-size
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
     {?since :since
      ?until :until}  :get-params
     get-req-config   :request-config
     :as              source-config
     :or              {get-batch-size   50
                       get-backoff-opts {:budget      10000
                                         :max-attempt 10}
                       poll-interval    1000}}
    :source
    {post-batch-size   :batch-size
     post-backoff-opts :backoff-opts
     post-req-config   :request-config
     :as               target-config
     :or               {post-backoff-opts {:budget      10000
                                           :max-attempt 10}}}
    :target
    filter-config :filter
    :keys
    [get-buffer-size
     statement-buffer-size
     batch-buffer-size
     batch-timeout
     cleanup-buffer-size]
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
            (max 1
                 (quot statement-buffer-size
                       post-batch-size)))
        cleanup-buffer-size
        (or cleanup-buffer-size
            get-batch-size
            0)
        source-version (get get-req-config :xapi-version "1.0.3")
        target-version (get post-req-config :xapi-version "1.0.3")]
    (->
     {:get-buffer-size       get-buffer-size
      :statement-buffer-size statement-buffer-size
      :batch-buffer-size     batch-buffer-size
      :batch-timeout         batch-timeout
      :cleanup-buffer-size   cleanup-buffer-size
      :source
      (-> source-config
          (assoc :batch-size get-batch-size
                 :backoff-opts get-backoff-opts
                 :poll-interval poll-interval)
          (assoc-in [:get-params :limit] get-batch-size)
          (cond->
              ?since (update-in [:get-params :since] t/normalize-stamp)
              ?until (update-in [:get-params :until] t/normalize-stamp)))
      :target
      (assoc target-config
             :batch-size post-batch-size
             :backoff-opts post-backoff-opts)
      :filter
      (or filter-config {})}
     (assoc-in [:source :request-config :xapi-version] source-version)
     (assoc-in [:target :request-config :xapi-version] target-version))))

(s/fdef sanitize-req-cfg
  :args (s/cat :rcfg ::client/request-config)
  :ret ::client/request-config)

(defn sanitize-req-cfg
  "Sanitize a single request config"
  [{:keys [password] :as rcfg}]
  (if password
    (assoc rcfg :password "************")
    rcfg))

(s/fdef sanitize
  :args (s/cat :config config-spec)
  :ret (s/and
        config-spec
        (fn [{{{src-pw :password} :request-config} :source
              {{tgt-pw :password} :request-config} :target}]
          (and (or (nil? src-pw)
                   (= "************" src-pw))
               (or (nil? tgt-pw)
                   (= "************" tgt-pw))))))

(defn sanitize
  "Sanitize a config, removing possibly sensitive values"
  [config]
  (-> config
      (update-in [:source :request-config] sanitize-req-cfg)
      (update-in [:target :request-config] sanitize-req-cfg)))

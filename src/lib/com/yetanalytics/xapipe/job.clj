(ns com.yetanalytics.xapipe.job
  "Immutable job configuration"
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.job.config :as config]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.job.state.errors :as errors]
            [com.yetanalytics.xapipe.util.time :as t]
            [xapi-schema.spec :as xs]))

(s/def ::config config/config-spec)
(s/def ::state state/state-spec)

(s/def ::id (s/and string? not-empty))

(def job-spec
  (s/keys
   :req-un [::id
            ::config
            ::state]))

;; Initialize a job
(s/fdef init-job
  :args (s/cat :id ::id
               :config ::config)
  :ret job-spec)

(defn init-job
  "Initialize a new job"
  [id
   {{{?since :since}  :get-params} :source
    filter-config :filter
    :as config}]
  {:id id
   :config
   (config/ensure-defaults config)
   :state
   {:status :init
    :cursor (or ?since "1970-01-01T00:00:00Z")
    :source {:errors []}
    :target {:errors []}
    :errors []
    :filter (if (:pattern filter-config)
              {:pattern {}}
              {})}})

;; Job-level state

;; State status
(s/fdef get-status
  :args (s/cat :job job-spec)
  :ret ::state/status)

(defn get-status
  "Get the current status of the job"
  [job]
  (get-in job [:state :status]))

(s/fdef errors?
  :args (s/cat :job job-spec)
  :ret boolean?)

(defn errors?
  "Check if a job has any errors"
  [{:keys [state]}]
  (state/errors? state))

(s/fdef sanitize
  :args (s/cat :job job-spec)
  :ret (s/and job-spec
              (fn [{{{{src-pw :password} :request-config} :source
                     {{tgt-pw :password} :request-config} :target}
                    :config}]
                (and (or (nil? src-pw)
                         (= "************" src-pw))
                     (or (nil? tgt-pw)
                         (= "************" tgt-pw))))))

(defn sanitize
  "Sanitize any sensitive info on a job for logging, etc"
  [job]
  (update job :config config/sanitize))

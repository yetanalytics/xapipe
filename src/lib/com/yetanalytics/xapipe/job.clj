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
   {{{?since :since}  :get-params
     get-batch-size   :batch-size
     get-backoff-opts :backoff-opts
     :as              source-config
     :or              {get-batch-size   50
                       get-backoff-opts {:budget      10000
                                         :max-attempt 10}}}
    :source
    {post-batch-size   :batch-size
     post-backoff-opts :backoff-opts
     :as               target-config
     :or               {post-backoff-opts {:budget      10000
                                           :max-attempt 10}}}
    :target
    :keys
    [get-buffer-size
     statement-buffer-size
     get-proc-conc
     batch-buffer-size
     batch-timeout]
    :or
    {get-buffer-size 10
     get-proc-conc   1
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
    {:id id
     :config
     {:get-buffer-size       get-buffer-size
      :statement-buffer-size statement-buffer-size
      :get-proc-conc         get-proc-conc
      :batch-buffer-size     batch-buffer-size
      :batch-timeout         batch-timeout
      :source
      (-> source-config
          (assoc :batch-size get-batch-size
                 :backoff-opts get-backoff-opts)
          (assoc-in [:get-params :limit] get-batch-size))
      :target
      (assoc target-config
             :batch-size post-batch-size)}
     :state
     {:status :init
      :cursor (or ?since "1970-01-01T00:00:00Z")
      :source {:errors []}
      :target {:errors []}
      :errors []}}))

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

(s/fdef update-job
  :args (s/cat
         :job job-spec
         :cursor (s/nilable ::state/cursor)
         :errors ::state/errors
         :command (s/? #{:complete :paused})))

(defn update-job
  "Attempt to update the job with the given cursor, errors,
  completion/pause status"
  [{:keys [state] :as job}
   ?cursor
   errors
   & [?command]]
  (assoc
   job
   :state
   (-> state
       ;; Update the cursor or set an error
       (cond->
           ?cursor (state/update-cursor ?cursor))
       ;; Add any passed-in errors
       (state/add-errors errors)
       ;; attempt to set desired command state or :running
       ;; will not happen with errors
       (state/set-status
        (or ?command :running)))))

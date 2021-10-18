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
               :source-config ::config/source
               :target-config ::config/target)
  :ret job-spec)

(defn init-job
  "Initialize a new job"
  [id
   {{?since :since} :get-params
    get-batch-size  :batch-size
    :as             source-config
    :or             {get-batch-size 50}}
   target-config]
  {:id id
   :config
   {:source
    (-> source-config
        (assoc :batch-size get-batch-size)
        (assoc-in [:get-params :limit] get-batch-size))
    :target target-config}
   :state
   {:status :init
    :cursor (or ?since "1970-01-01T00:00:00Z")
    :source {:errors []}
    :target {:errors []}
    :errors []}})

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

(comment

  (s/explain job-spec
             (init-job "foo"
                       {:request-config {:url-base    "http://localhost:8080"
                                         :xapi-prefix "/xapi"}
                        :get-params     {:format "exact"}
                        :poll-interval  1000
                        :batch-size     50}
                       {:request-config {:url-base    "http://localhost:8081"
                                         :xapi-prefix "/xapi"}
                        :batch-size     50})
             )

  (update-job
   {:id "foo",
    :config
    {:source
     {:request-config
      {:url-base "http://localhost:8080", :xapi-prefix "/xapi"},
      :get-params    {:limit 50},
      :poll-interval 1000,
      :batch-size    50},
     :target
     {:request-config
      {:url-base "http://localhost:8081", :xapi-prefix "/xapi"},
      :batch-size 50}},
    :state
    {:status :init,
     :cursor "1970-01-01T00:00:00Z",
     :source {:errors []},
     :target {:errors []},
     :errors []}}
   "1980-01-01T00:00:00Z"
   [#_{:type :job
       :msg  "Oh No!"}]
   :complete)

  )

(ns com.yetanalytics.xapipe.job
  "Immutable job configuration"
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.job.config :as config]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.job.state.errors :as errors]
            [xapipe.util.time :as t]
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

;; Check for errors

(s/fdef errors?
  :args (s/cat :job job-spec)
  :ret boolean?)

(defn errors?
  "Check if a job has any errors"
  [{{job-errors              :errors
     {source-errors :errors} :source
     {target-errors :errors} :target} :state}]
  (some? (not-empty (concat job-errors source-errors target-errors))))

;; Summarize errors

(s/fdef get-errors
  :args (s/cat :job job-spec)
  :ret (s/tuple ::state/errors
                ::state/errors
                ::state/errors))

(defn get-errors
  "Get all errors for [:job :source :target]"
  [{{job-errors              :errors
     {source-errors :errors} :source
     {target-errors :errors} :target} :state}]
  [job-errors
   source-errors
   target-errors])

(s/fdef add-error
  :args (s/cat :job job-spec
               :error-type #{:job :source :target}
               :error ::errors/error)
  :ret job-spec)

(defn add-error
  "Add an error to the job"
  [job error-type error]
  (-> job
      (update-in
       (case error-type
         :job    [:state :errors]
         :source [:state :source :errors]
         :taget  [:state :target :errors]))
      (assoc-in [:state :status] :errors)))

;; State status

(s/fdef get-status
  :args (s/cat :job job-spec)
  :ret ::state/status)

(defn get-status
  "Get the current status of the job"
  [job]
  (get-in job [:state :status]))

(s/fdef set-status
  :args (s/cat :job job-spec
               :new-status #{:running ;; in progress
                             :complete ;; complete
                             :paused ;; manual stop/pause
                             })
  :ret job-spec)

(defn set-status
  "Attempt to set the desired status, only on valid transitions"
  [{{:keys [status]} :state
    :as              job} new-status]
  (cond
    ;; Don't modify state for an invalid transition
    (not (contains? state/valid-status-transitions
                    [status new-status])) job
    ;; Ensure errors are cleared before allowing state change
    (and
     (contains? #{[:error :running]
                  [:error :paused]} [status new-status])
     (not= [[] [] []] (get-errors job)))  job

    :else (assoc-in [:state :status] new-status)))

(s/fdef update-cursor
  :args (s/cat :job job-spec
               :new-cursor ::xs/timestamp)
  :ret job-spec)

(defn update-cursor
  "Attempt to update the since cursor on the job"
  [job new-cursor]
  (let [job-after (set-status job)]
    (if (not= job job-after)
      (update-in job-after
                 [:state :cursor]
                 (comp
                  t/normalize-stamp
                  t/latest-stamp) new-cursor)
      job)))

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



  )

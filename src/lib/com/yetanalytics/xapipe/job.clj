(ns com.yetanalytics.xapipe.job
  "Immutable job configuration"
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.job.config :as config]
            [com.yetanalytics.xapipe.job.state :as state]))

(s/def ::config config/config-spec)
(s/def ::state state/state-spec)

(s/def ::id (s/and string? not-empty))

(def job-spec
  (s/keys
   :req-un [::id
            ::config
            ::state]))

;; Check for errors

(s/fdef errors?
  :args (s/cat :job job-spec)
  :ret boolean?)

(defn errors?
  "Check if a job has any errors"
  [{{job-errors :errors
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
  [{{job-errors :errors
     {source-errors :errors} :source
     {target-errors :errors} :target} :state}]
  [job-errors
   source-errors
   target-errors])

(comment

  (get-errors
   {:id "foo"
    :config
    {:source {:request-config {:url-base    "http://localhost:8080"
                               :xapi-prefix "/xapi"}
              :poll-interval  1000
              :batch-size     50}
     :target {:request-config {:url-base    "http://localhost:8081"
                               :xapi-prefix "/xapi"}
              :batch-size     50}}
    :state
    {:cursor "1970-01-01T00:00:00Z"
     :source {:errors [#_{:message "foo"}]}
     :target {:errors [#_{:message "foo"}]}
     :errors [#_{:message "foo"}]}})



  )

(ns com.yetanalytics.xapipe.job
  "Immutable job configuration"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.yetanalytics.xapipe.job.config :as config]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.job.state.errors :as errors]
            [com.yetanalytics.xapipe.util.time :as t]
            [xapi-schema.spec :as xs]))

(def current-version 0)

(s/def ::config config/config-spec)
(s/def ::state state/state-spec)
(s/def ::version #{current-version})
(s/def ::id (s/and string? not-empty))

(defn valid-get-params-vs-state?
  "Verify that since and until params (if present) wrap the cursor."
  [{{{{?since :since
       ?until :until} :get-params} :source} :config
    {:keys [cursor]} :state}]
  (t/in-order?
   (concat
    (when ?since
      [?since])
    [cursor]
    (when ?until
      [?until]))))

(def job-spec-base
  (s/keys
   :req-un [::id
            ::config
            ::state]
   :opt-un [::version]))

(def job-spec
  (s/with-gen
    (s/and
     job-spec-base
     valid-get-params-vs-state?)
    (fn []
      (sgen/fmap
       (fn [[job stamps]]
         (let [[since cursor until] (sort stamps)]
           (cond-> (assoc-in job [:state :cursor] cursor)
             (get-in job [:config :source :get-params :since])
             (assoc-in [:config :source :get-params :since] since)

             (get-in job [:config :source :get-params :until])
             (assoc-in [:config :source :get-params :until] until))))
       (sgen/tuple
        (s/gen job-spec-base)
        (sgen/vector-distinct
         (s/gen ::t/normalized-stamp)
         {:num-elements 3}))))))

(defmulti inc-version
  "Upgrade a job to the next version up"
  (fn [{:keys [version]}]
    [version (inc version)]))

(defmethod inc-version :default
  [{:keys [version]}]
  (let [next-version (inc version)]
    (throw
     (ex-info (format "No known route from version %d to %d"
                      version
                      next-version)
              {:type ::no-known-upgrade
               :version version
               :next-version next-version}))))

(s/fdef upgrade-job
  :args (s/cat :maybe-job
               (s/with-gen
                 map? ;; loose spec here, since we don't spec old versions
                 (fn [] (s/gen job-spec))))
  :ret job-spec)

(defn upgrade-job
  "Attempt to upgrade a job to the current version or throw.
  Assumes no version to be the latest version."
  [{:keys [version]
    :as job
    :or {version current-version}}]
  (cond
    (= current-version version) (assoc job :version version)
    (< current-version version) (throw
                                 (ex-info (format "Unknown version %d"
                                                  version)
                                          {:type ::unknown-version
                                           :version version
                                           :current-version current-version}))
    :else
    (recur (inc-version job))))

;; Initialize a job
(s/fdef init-job
  :args (s/cat :id ::id
               :config ::config)
  :ret job-spec)

(defn init-job
  "Initialize a new job"
  [id
   config]
  (let [{{{?since :since} :get-params} :source
         filter-config :filter
         :as config} (config/ensure-defaults config)]
    {:id id
     :version current-version
     :config
     config
     :state
     {:status :init
      :cursor (or ?since
                  "1970-01-01T00:00:00.000000000Z")
      :source {:errors []}
      :target {:errors []}
      :errors []
      :filter (if (:pattern filter-config)
                {:pattern {}}
                {})}}))

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

(s/fdef all-errors
  :args (s/cat :job job-spec)
  :ret ::state/errors)

(defn all-errors
  "Get all errors for the job of any type"
  [job]
  (apply concat (state/get-errors (:state job))))

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

(s/fdef reconfigure-job
  :args (s/cat :job (s/with-gen job-spec
                      (fn []
                        (sgen/fmap
                         #(update % :config config/ensure-defaults)
                         (s/gen job-spec))))
               :config ::config)
  :ret job-spec)

(defn reconfigure-job
  "Given a job and a new config, return the job with the config applied, and
  state adjusted if possible.
  If the resulting job would be invalid, we add an error to the job state."
  [{{{?old-since :since
      ?old-until :until} :get-params} :config
    {:keys [status cursor]
     filter-state :filter} :state
    :as job}
   {{{?new-since :since
      ?new-until :until} :get-params} :source
    filter-cfg :filter
    :as config}]
  (if (= status :error)
    (-> job
        (update :state
                state/add-error
                {:type :job
                 :message "Cannot reconfigure job with errors."})
        (update :state state/set-updated))
    (let [?since (or ?new-since ?old-since)
          ?until (or ?new-until ?old-until)]
      (if (t/in-order?
           (concat
            (when ?since
              [?since])
            [cursor]
            (when ?until
              [?until])))
        (-> job
            (assoc :config config)
            (cond->
                (and
                 (:pattern filter-cfg)
                 (not (:pattern filter-state)))
              (assoc-in [:state :filter :pattern] {})))
        (-> job
            (update :state
                    state/add-error
                    {:type :job
                     :message "since, cursor, until must be ordered!"})
            (update :state state/set-updated))))))

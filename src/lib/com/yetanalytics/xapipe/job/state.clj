(ns com.yetanalytics.xapipe.job.state
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.yetanalytics.xapipe.filter :as filt]
            [com.yetanalytics.xapipe.job.state.errors :as errors]
            [com.yetanalytics.xapipe.util.time :as t]
            [xapi-schema.spec :as xs]))

;; Error vectors are stored at 3 levels for the source,
;; target, and entire job.
(s/def ::errors errors/errors-spec)

;; Cursor is a timestamp representing the latest stored time on a received
;; statement OR in the case of polling the last consistent-through given
;; without data.
;; It should only be persisted when it represents data successfully copied from
;; source to target!

(s/def ::cursor ::t/normalized-stamp)

(s/def ::source
  (s/keys :req-un [::errors]))

(s/def ::target
  (s/keys :req-un [::errors]))

(s/def ::status
  #{:init ;; not started
    :running ;; in progress
    :complete ;; all desired data transferred
    :error ;; stopped with errors
    :paused ;; manual stop/pause
    })

(s/def ::filter
  (s/with-gen filt/filter-state-spec
    (fn []
      (sgen/return {}))))

(def state-spec
  (s/keys :req-un [::source
                   ::target
                   ::errors
                   ::cursor
                   ::status
                   ::filter]))

(s/fdef errors?
  :args (s/cat :state state-spec)
  :ret boolean?)

(defn errors?
  "Check for errors left in the state"
  [{job-errors              :errors
    {source-errors :errors} :source
    {target-errors :errors} :target}]
  (some? (not-empty (concat job-errors source-errors target-errors))))

(s/fdef get-errors
  :args (s/cat :state state-spec)
  :ret (s/tuple ::errors
                ::errors
                ::errors))

(defn get-errors
  "Get all errors for [:job :source :target]"
  [{job-errors              :errors
    {source-errors :errors} :source
    {target-errors :errors} :target}]
  [job-errors
   source-errors
   target-errors])

;; Update

(s/fdef add-error
  :args (s/cat :state state-spec
               :error ::errors/error)
  :ret state-spec)

(defn add-error
  "Add an error to the state"
  [state {etype :type
          :as   error}]
  (-> state
      (update-in
       (case etype
         :job    [:errors]
         :source [:source :errors]
         :target [:target :errors])
       conj error)
      ;; Make sure the status is error
      (assoc :status :error)))

(s/fdef add-errors
  :args (s/cat :state state-spec
               :errors ::errors)
  :ret state-spec)

(defn add-errors
  "Add multiple errors"
  [state errors]
  (reduce (fn [s e]
            (add-error s e))
          state
          errors))

(s/fdef clear-errors
  :args (s/cat :state state-spec)
  :ret state-spec)

(defn clear-errors
  [state]
  (-> state
      (update :errors empty)
      (update-in [:source :errors] empty)
      (update-in [:target :errors] empty)))

(s/fdef update-cursor
  :args (s/cat :state state-spec
               :new-cursor ::cursor)
  :ret state-spec)

(defn update-cursor
  "Attempt to update the since cursor. Add error if we try to go backwards."
  [{old-cursor :cursor
    :as        state} new-cursor]
  (let [[a b]    (sort [old-cursor
                        new-cursor])]
    (cond
      ;; no change, return
      (= a b)
      state

      ;; update
      (= [a b] [old-cursor new-cursor])
      (assoc state :cursor new-cursor)

      ;; can't go backwards
      (= [b a] [old-cursor new-cursor])
      (add-error
       state
       {:type    :job
        :message (format "New cursor %s is before current cursor %s"
                         new-cursor old-cursor)}))))

(def valid-status-transitions
  #{[:init :running] ;; start
    [:init :error] ;; can't start
    [:init :complete] ;; no data

    [:running :complete] ;; until reached/exit
    [:running :error] ;; runtime error
    [:running :paused] ;; user pause
    [:running :running] ;; cursor update

    [:paused :running] ;; resume
    [:paused :error] ;; can't resume
    [:paused :paused] ;; immediate stop on resume

    [:error :running] ;; if errors clear
    [:error :paused] ;; same
    [:error :error] ;; more/less errors
    })

(s/fdef set-status
  :args (s/cat :state state-spec
               :new-status #{:running ;; in progress
                             :complete ;; complete
                             :paused ;; manual stop/pause
                             })
  :ret state-spec)

(defn set-status
  "Attempt to set the desired status, only on valid transitions"
  [{:keys [status] :as state} new-status]
  (cond
    ;; Invalid status transition error
    (not (contains? valid-status-transitions
                    [status new-status]))
    (add-error state
               {:type    :job
                :message (format "Invalid status transition: %s %s"
                                 status new-status)})

    ;; Ensure errors are cleared before allowing state change
    (and
     (contains? #{[:error :running]
                  [:error :paused]} [status new-status])
     (not= [[] [] []] (get-errors state)))
    (add-error state
               {:type    :job
                :message "Cannot start or pause job with errors."})

    :else (assoc state :status new-status)))

(s/fdef update-filter
  :args (s/cat :state state-spec
               :filter-state ::filter)
  :ret state-spec)

(defn update-filter
  "Update filter state"
  [{:keys [status] :as state} filter-state]
  (if (= :error status)
    (add-error state
               {:type :job
                :message "Cannot update filter on job with errors"})
    (assoc state :filter filter-state)))

(ns com.yetanalytics.xapipe.job.state
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.xapipe.job.state.errors :as errors]))

;; Error vectors are stored at 3 levels for the source,
;; target, and entire job.
(s/def ::errors errors/errors-spec)

;; Cursor is a timestamp representing the latest stored time on a received
;; statement OR in the case of polling the last consistent-through given
;; without data.
;; It should only be persisted when it represents data successfully copied from
;; source to target!

(s/def ::cursor ::xs/timestamp)

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

(def valid-status-transitions
  #{[:init :running] ;; start
    [:init :error] ;; can't start
    [:init :complete] ;; no data

    [:running :complete] ;; until reached/exit
    [:running :error] ;; runtime error
    [:running :paused] ;; user pause
    [:running :running] ;; cursor update

    [:paused :running] ;; resume

    [:error :running] ;; if errors clear
    [:error :paused] ;; same
    [:error :error] ;; more/less errors
    })

(def state-spec
  (s/keys :req-un [::source
                   ::target
                   ::errors
                   ::cursor
                   ::status]))

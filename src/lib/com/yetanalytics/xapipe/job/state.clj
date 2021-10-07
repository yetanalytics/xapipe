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

(def state-spec
  (s/keys :req-un [::source
                   ::target
                   ::errors
                   ::cursor]))

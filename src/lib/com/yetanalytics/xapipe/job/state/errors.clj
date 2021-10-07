(ns com.yetanalytics.xapipe.job.state.errors
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]))

(s/def ::message string?)
(s/def ::error (s/keys :req-un [::message]))

(def errors-spec (s/every ::error))

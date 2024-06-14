(ns com.yetanalytics.xapipe.job.state.errors
  (:require [clojure.spec.alpha :as s]))

(s/def ::message string?)
(s/def ::type #{:job :source :target})
(s/def ::error (s/keys :req-un [::message
                                ::type]))

(def errors-spec (s/every ::error
                          :gen-max 5))

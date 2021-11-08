(ns com.yetanalytics.xapipe.spec.common
  "Common & Utility Specs"
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async.impl.protocols :as aproto]))

(defn channel?
  [x]
  (satisfies? aproto/Channel x))

(s/def ::channel channel?)

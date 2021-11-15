(ns com.yetanalytics.xapipe.metrics
  "Metrics protocol called by xapipe lib.
  Inspired by https://github.com/pedestal/pedestal/blob/master/log/src/io/pedestal/log.clj"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]))

(defprotocol Reporter
  (-gauge [this k v]
    "Set a gauge to an arbitrary value")
  (-counter [this k delta]
    "Increase a counter by delta")
  (-histogram [this k v]
    "Log an observation of a value")
  (-flush! [this]
    "Flush metrics out if possible"))

(deftype NoopReporter []
  Reporter
  (-gauge [this _ _]
    this)
  (-counter [this _ _]
    this)
  (-histogram [this _ _]
    this)
  (-flush! [this]
    this))

(s/def ::reporter
  (s/with-gen #(satisfies? Reporter %)
    (fn []
      (sgen/return (->NoopReporter)))))

(s/fdef gauge
  :args (s/cat :reporter ::reporter
               :k keyword?
               :v number?)
  :ret ::reporter)

(defn gauge
  "Set a gauge to an arbitrary value"
  [reporter k v]
  (-gauge reporter k v))

(s/fdef counter
  :args (s/cat :reporter ::reporter
               :k keyword?
               :delta nat-int?)
  :ret ::reporter)

(defn counter
  "Increase a counter by delta"
  [reporter k delta]
  (-counter reporter k delta))

(s/fdef histogram
  :args (s/cat :reporter ::reporter
               :k keyword?
               :v number?)
  :ret ::reporter)

(defn histogram
  "Log an observation of a value"
  [reporter k v]
  (-histogram reporter k v))

(s/fdef flush!
  :args (s/cat :reporter ::reporter)
  :ret ::reporter)

(defn flush!
  "Flush metrics out if possible"
  [reporter]
  (-flush! reporter))

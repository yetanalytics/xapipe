(ns com.yetanalytics.xapipe.metrics.impl.prometheus
  "Prometheus metrics implementation for use by the prometheus CLI"
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.metrics :as metrics]
            [iapetos.core :as pro]
            [iapetos.export :as pro-exp]
            [iapetos.collector.jvm :as pro-jvm]))

(s/def ::push-gateway string?)

(s/fdef prometheus-push-reporter
  :args (s/cat :push-gateway ::push-gateway
               :job-id ::job/id)
  :ret ::metrics/reporter)

(def collectors
  (concat
   (map
    pro/gauge
    metrics/gauge-keys)
   (map
    pro/counter
    metrics/counter-keys)
   (map
    pro/histogram
    metrics/histogram-keys)
   (map
    pro/summary
    metrics/summary-keys)
   [(pro-jvm/standard)
    (pro-jvm/gc)
    (pro-jvm/memory-pools)
    (pro-jvm/threads)]))

(defn prometheus-push-reporter
  [push-gateway
   job-id]
  (let [registry (reduce
                  pro/register
                  (pro-exp/pushable-collector-registry
                   {:push-gateway push-gateway
                    :job job-id})
                  collectors)]
    (reify
      metrics/Reporter
      (-gauge [_ k v]
        (pro/set registry k v))
      (-counter [_ k delta]
        (pro/inc registry k delta))
      (-histogram [_ k v]
        (pro/observe registry k v))
      (-summary [_ k v]
        (pro/observe registry k v))
      (-flush! [_]
        (pro-exp/push! registry)))))

(ns com.yetanalytics.xapipe.util
  "Utility functions"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]))

(def pos-int-safe-gen
  (s/with-gen pos-int?
    (fn []
      (sgen/large-integer* {:min 1 :max 100 :NaN? false}))))

(def nat-int-safe-gen
  (s/with-gen nat-int?
    (fn []
      (sgen/large-integer* {:min 0 :max 100 :NaN? false}))))

;; From `lrsql`

(s/def ::budget      pos-int-safe-gen)
(s/def ::max-attempt pos-int-safe-gen)
(s/def ::j-range     nat-int-safe-gen)
(s/def ::initial     nat-int-safe-gen)

(def backoff-opts-spec
  (s/keys :req-un [::budget ::max-attempt]
          :opt-un [::j-range ::initial]))

(s/fdef backoff-ms
  :args (s/cat :attempt nat-int?
               :opts backoff-opts-spec)
  :ret (s/nilable nat-int?))

(defn backoff-ms
  "Take an `attempt` number and an opts map containing the total `:budget`
   in ms and an `:max-attempt` number and return a backoff time in ms.
   Can also optionally provide a jitter in `j-range` ms and an `initial` ms
   amount of delay to be used first in the opts map."
  [attempt {:keys [budget max-attempt j-range initial]
            :or {j-range 10}}]
  (let [jitter (rand-int j-range)]
    (cond
      (= attempt 0)           0
      (> attempt max-attempt) nil
      (and (some? initial)
           (= attempt 1))     (+ initial jitter)
      :else                   (int (+ (* budget (/ (Math/pow 2 (- attempt 1))
                                                   (Math/pow 2 max-attempt)))
                                      jitter)))))

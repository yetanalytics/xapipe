(ns com.yetanalytics.xapipe.bench.maths
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as cset]))

;; Origninally Cribbed from Avery and Jean
;; https://github.com/clojure-cookbook/clojure-cookbook/blob/master/01_primitive-data/1-20_simple-statistics.asciidoc

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mean
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mean ^Double [coll]
  (let [[sum count] (reduce (fn [[s c] x]
                              [(+ s x) (inc c)])
                            [0.0 0]
                            coll)]
    (if (pos? count)
      (double (/ sum count))
      0.0)))

(s/fdef mean
        :args (s/cat :coll (s/coll-of number?))
        :ret double?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Median
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn median ^Double [coll]
  (let [sorted (sort coll)
        cnt (count sorted)]
    (when (< 0 cnt)
      (let [halfway (quot cnt 2)]
        (double (if (odd? cnt)
                  (nth sorted halfway)
                  (let [bottom (dec halfway)
                        bottom-val (nth sorted bottom)
                        top-val (nth sorted halfway)]
                    (mean [bottom-val top-val]))))))))

(s/fdef median
        :args (s/cat :coll (s/coll-of number?))
        :ret (s/nilable double?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mode
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mode [coll]
  (if-let [coll (not-empty coll)]
    (let [freqs (frequencies coll)
          occurrences (group-by val freqs)
          modes (last (sort occurrences))
          modes (->> modes
                     val
                     (map key))]
      modes)
    (list)))

(s/fdef mode
        :args (s/cat :coll (s/coll-of any?))
        :ret (s/coll-of any?)
        :fn (fn [{{:keys [coll]} :args
                  ret            :ret}]
              (cond
                (= 1 (count coll))
                (= coll ret)

                (seq coll)
                (not-empty (cset/intersection
                            (set ret)
                            (set coll)))

                (empty? coll)
                (empty? ret)
                    :else (println coll ret))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Standard Deviation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn stddev ^Double [coll & {:keys [complete-population?]
                              :or {complete-population? false}}]
  (let [total (count coll)]
    (if (< 1 total)
      (let [avg (mean coll)
            squares (for [x coll]
                      (let [x-avg (- x avg)]
                        (* x-avg x-avg)))]
        (double
         (-> (/ (apply + squares)
                (if complete-population?
                  total
                  (- total 1)))
             (Math/sqrt))))
      0.0)))

(s/def ::complete-population?
  boolean?)

(s/fdef stddev
        :args (s/cat :coll (s/coll-of number?)
                     :kwargs (s/? (s/keys* :opt-un [::complete-population?])))
        :ret double?)

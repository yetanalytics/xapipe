(ns com.yetanalytics.xapipe.util.async
  "Useful Async facilities"
  (:require [clojure.core.async :as a]))

(defn batch-filter
  "Given a channel a, get and attempt to batch records by size, sending them to
  channel b. If channel a is parked for longer than timeout-ms, send a partial
  batch.
  If :predicates or :stateful-predicates, maps of id keys to predicates, are
  provided, use them to filter statements.

  Stateful predicate state will be provided on the enclosing batch map by key.

  To provide initial state, supply a :init-states key, which should contain
  state for each stateful predicate.

  If :cleanup-fn is provided, run it on dropped records"
  [a b size timeout-ms
   & {:keys [predicates
             stateful-predicates
             init-states
             cleanup-fn]
      :or {predicates {}
           stateful-predicates {}}}]
  (let [stateless-pred (if (empty? predicates)
                         (constantly true)
                         (apply every-pred (vals predicates)))]
    (a/go-loop [buf []
                states (or init-states
                           (into {}
                                 (for [[k p] stateful-predicates]
                                   [k {}])))]
      ;; Send if the buffer is full
      (if (= size (count buf))
        (do (a/>! b {:batch buf})
            (recur [] states))
        (let [timeout-chan (a/timeout timeout-ms)
              [v p] (a/alts! [a timeout-chan])]
          (if (identical? p timeout-chan)
            ;; We've timed out. Flush!
            (do
              (when (not-empty buf)
                (a/>! b {:batch buf}))
              (recur [] states))
            (if-not (nil? v)
              ;; We have a record
              (if (stateless-pred v)
                ;; If stateless passes, we do any stateful
                (if (not-empty stateful-predicates)
                  (let [checks (into {}
                                     (for [[k p] stateful-predicates]
                                       [k (p (get states k) v)]))
                        pass? (every? (comp true? second)
                                      (vals checks))]
                    (when-not pass?
                      (when cleanup-fn
                        (cleanup-fn v)))
                    (recur
                     (if pass?
                       (conj buf v)
                       buf)
                     ;; new states
                     (reduce-kv
                      (fn [m k v]
                        (assoc m k (first v)))
                      {}
                      checks)))
                  ;; Just stateless, OK to pass
                  (recur (conj buf v) states))
                (do
                  (when cleanup-fn
                    (cleanup-fn v))
                  (recur buf states)))
              ;; A is closed, we should close B
              (do
                ;; But only after draining anything in the buffer
                (when (not-empty buf)
                  (a/>! b {:batch buf}))
                (a/close! b)))))))))

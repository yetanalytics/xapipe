(ns com.yetanalytics.xapipe.util.async
  "Useful Async facilities"
  (:require [clojure.core.async :as a]))

(defn batch
  "Given a channel a, get and attempt to batch records by size, sending them to
  channel b. If channel a is parked for longer than timeout-ms, send a partial
  batch"
  [a b size timeout-ms]
  (a/go-loop [buf []]
    ;; Send if the buffer is full
    (if (= size (count buf))
      (do (a/>! b buf)
          (recur []))
      (let [timeout-chan (a/timeout timeout-ms)
            [v p] (a/alts! [a timeout-chan])]
        (if (identical? p timeout-chan)
          ;; We've timed out. Flush!
          (do
            (when (not-empty buf)
              (a/>! b buf))
            (recur []))
          (if-not (nil? v)
            ;; We have a record
            (recur (conj buf v))
            ;; A is closed, we should close B
            (do
              ;; But only after draining anything in the buffer
              (when (not-empty buf)
                (a/>! b buf))
              (a/close! b))))))))

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
             cleanup-fn]}]
  (let [stateless-pred (if (empty? predicates)
                         (constantly true)
                         (apply some-fn (vals predicates)))]
    (a/go-loop [buf []
                states (or init-states
                           (into {}
                                 (for [[k p] stateful-predicates]
                                   [k {}])))]
      ;; Send if the buffer is full
      (if (= size (count buf))
        (do (a/>! b buf)
            (recur [] states))
        (let [timeout-chan (a/timeout timeout-ms)
              [v p] (a/alts! [a timeout-chan])]
          (if (identical? p timeout-chan)
            ;; We've timed out. Flush!
            (do
              (when (not-empty buf)
                (a/>! b buf))
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
                  (a/>! b buf))
                (a/close! b)))))))))

(comment
  (do
    ;; A channel that will get numbers at random intervals forever
    ;; until I close it
    (def a-chan (a/chan))

    ;; The loop for a-chan
    (a/go-loop [n 0]
      (when (a/>! a-chan n) ;; terminate if closed!
        ;; Wait a random interval
        (a/<! (a/timeout (rand-int 100)))
        ;; DO IT AGAIN
        (recur (inc n))))

    ;; Where the batches go
    (def b-chan (a/chan))

    ;; b-chan printer
    (a/go-loop [batch-idx 0]
      (when-let [batch (a/<! b-chan)]
        (println 'batch batch-idx
                 'count (count batch)
                 'range [(first batch) (last batch)])
        (recur (inc batch-idx))))

    ;; Batch a-chan to b-chan
    (batch a-chan
           b-chan
           50
           500))

  (a/close! a-chan)


  )

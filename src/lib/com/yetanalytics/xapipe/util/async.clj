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
  Stateful predicate state will be provided on the enclosing batch map by key
  If :cleanup-fn is provided, run it on dropped records"
  [a b size timeout-ms
   & {:keys [predicates
             stateful-predicates
             cleanup-fn]}]
  (let [stateless-pred (if (empty? predicates)
                         identity
                         (apply some-fn (vals predicates)))]
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
              (if (stateless-pred v)
                (recur (conj buf v))
                (do
                  (when cleanup-fn
                    (cleanup-fn v))
                  (recur buf)))
              ;; A is closed, we should close B
              (do
                ;; But only after draining anything in the buffer
                (when (not-empty buf)
                  (a/>! b buf))
                (a/close! b)))))))))


#_(defn filter-stateless
  "Stateless predicate filter, dropped records will have cleanup-fn run on them
  if provided, in a thread."
  [in-chan
   pred
   out-chan
   & {:keys [cleanup-fn]}]
  (a/go-loop []
    (if-let [record (a/<! in-chan)]
      (do
        (if (pred record)
          (a/>! out-chan record)
          (if cleanup-fn
            (a/thread (cleanup-fn record))))
        (recur))
      (a/close! out-chan))))

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

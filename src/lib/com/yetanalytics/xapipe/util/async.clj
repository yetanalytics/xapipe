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
            (a/close! b)))))))

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

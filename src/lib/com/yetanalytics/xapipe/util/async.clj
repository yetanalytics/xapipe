(ns com.yetanalytics.xapipe.util.async
  "Useful Async facilities"
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.metrics :as metrics]
            [com.yetanalytics.xapipe.spec.common :as cspec]))

(s/def ::stateless-predicates
  (s/map-of
   keyword?
   ifn?))

(s/def ::stateful-predicates
  (s/map-of
   keyword?
   ifn?))

(s/def ::init-states
  (s/map-of
   keyword?
   any?))

(s/def ::cleanup-fn
  fn?)

(s/def ::emit-fn
  fn?) ;; TODO: fspec

(s/fdef batch-filter
  :args (s/cat :a ::cspec/channel
               :b ::cspec/channel
               :size pos-int?
               :timeout-ms nat-int?
               :kwargs (s/keys*
                        :opt-un [::stateless-predicates
                                 ::stateful-predicates
                                 ::init-states
                                 ::cleanup-fn
                                 ::metrics/reporter
                                 ::emit-fn])))

(defn- default-emit-fn
  [batch filter-state last-dropped]
  (when (or (not-empty batch) last-dropped)
    {:batch batch
     :filter-state filter-state
     :last-dropped last-dropped}))

(defn- apply-stateful-predicates
  [states stateful-predicates v]
  (if (not-empty stateful-predicates)
    (let [checks (into {}
                       (for [[k p] stateful-predicates]
                         [k (p (get states k) v)]))
          pass? (every? (comp true? second)
                        (vals checks))]
      {:pass? pass?
       :states (reduce-kv
                (fn [m k v]
                  (assoc m k (first v)))
                {}
                checks)})
    {:pass? true
     :states states}))

(defn- apply-predicates
  [states stateless-pred stateful-predicates v]
  (let [stateless-pass? (stateless-pred v)
        {stateful-pass? :pass?
         states :states} (apply-stateful-predicates
                          states
                          stateful-predicates
                          v)]
    {:pass? (and stateless-pass?
                 stateful-pass?)
     :states states}))

(defn batch-filter
  "Given a channel a, get and attempt to batch records by size, sending them to
  channel b. If channel a is parked for longer than timeout-ms, send a partial
  batch.
  If :predicates or :stateful-predicates, maps of id keys to predicates, are
  provided, use them to filter statements.

  Stateful predicate state will be provided on the enclosing batch map by key.

  To provide initial state, supply a :init-states key, which should contain
  state for each stateful predicate.

  If :cleanup-fn is provided, run it on dropped records

  Will remember the last record dropped for use in cursors and such

  Returns channel b"
  [a b size timeout-ms
   & {:keys [stateless-predicates
             stateful-predicates
             init-states
             cleanup-fn
             reporter
             emit-fn]
      :or {stateless-predicates {}
           stateful-predicates {}
           reporter (metrics/->NoopReporter)
           emit-fn default-emit-fn}}]
  (let [stateless-pred (if (empty? stateless-predicates)
                         (constantly true)
                         (apply every-pred (vals stateless-predicates)))
        init-states (or init-states
                        (into {}
                              (for [[k p] stateful-predicates]
                                [k {}])))
        buffer (a/buffer size)
        buf-chan (a/chan buffer)]
    (a/go-loop [states init-states
                last-dropped nil]
      (let [buf-count (count buffer)]
        ;; Send if the buffer is full
        (if (= size buf-count)
          (do
            (when-let [emit-event (emit-fn
                                   (a/<!
                                    (a/into []
                                            (a/take buf-count buf-chan)))
                                   states
                                   last-dropped)]
              (a/>! b emit-event))
            (recur states nil))
          (let [timeout-chan (a/timeout timeout-ms)
                [v p] (a/alts! [a timeout-chan])]
            (if (identical? p timeout-chan)
              ;; We've timed out. Flush!
              (do
                (when-let [emit-event (emit-fn
                                       (a/<!
                                        (a/into []
                                                (a/take buf-count buf-chan)))
                                       states
                                       last-dropped)]
                  (a/>! b emit-event))
                (recur states nil))
              (if-not (nil? v)
                ;; We have a record
                (let [{:keys [pass?]
                       new-states :states} (apply-predicates
                                            states
                                            stateless-pred
                                            stateful-predicates
                                            v)]

                  (if pass?
                    (do
                      (a/>! buf-chan v)
                      (recur new-states nil))
                    (do
                      (when cleanup-fn
                        (cleanup-fn v))
                      (recur new-states v))))
                ;; A is closed, we should close B
                (do
                  ;; But only after draining anything in the buffer
                  (when-let [emit-event (emit-fn
                                         (a/<!
                                          (a/into []
                                                  (a/take buf-count buf-chan)))
                                         states
                                         last-dropped)]
                    (a/>! b emit-event))
                  (a/close! b))))))))
    b))

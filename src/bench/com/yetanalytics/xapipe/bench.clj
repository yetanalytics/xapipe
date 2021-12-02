(ns com.yetanalytics.xapipe.bench
  (:require [com.yetanalytics.xapipe.test-support :as sup]
            [com.yetanalytics.xapipe :as xapipe]
            [clojure.core.async :as a]
            [clojure.pprint :as pprint]
            [com.yetanalytics.xapipe.bench.maths :as maths]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [com.yetanalytics.xapipe.util.time :as tu]
            [java-time :as t]
            [java-time.seqs :as tseq])
  (:import [java.time Instant]))

(defn- stamp-seq
  "Create a lazy sequence of timestamps"
  []
  (map
   tu/normalize-inst
   (tseq/iterate
    t/plus
    (t/instant)
    (t/millis 1))))

(defn write-payload
  "Write a large number of statements to disk"
  [{:keys [num-statements
           profile
           seed
           out]
    :or {num-statements 10000
         profile "dev-resources/profiles/calibration.jsonld"
         seed 42
         out "dev-resources/bench/payload.json"}}]
  (with-open [w (io/writer out)]
    (doseq [s (map
               (fn [stored s]
                 (assoc s "stored" stored))
               (stamp-seq)
               (sup/gen-statements
                num-statements
                :profiles
                [profile]
                :parameters {:seed seed}))]
      (json/generate-stream s w))))

(defn run-source-lrs
  "Run an LRS that will stream out statements from a file"
  [{:keys [payload-path
           port]
    :or {payload-path "dev-resources/bench/payload.json"
         port 8080}}]
  (let [lrs (sup/lrs
             :stream-path payload-path
             :port port)]
    ((:start lrs))))

(defn run-target-lrs
  "Run a dummy LRS that will accept statements but not store them"
  [{:keys [port]
    :or {port 8081}}]
  (let [lrs (sup/lrs
             :sink true
             :port port)]
    ((:start lrs))))

(defn- job-time-ms
  "Derive total job time in ms from run states"
  [states]
  (-
   (-> states last :state :updated tu/parse-inst inst-ms)
   (-> states first :state :updated tu/parse-inst inst-ms)))

(def col-headers
  {:total-statements "statements"
   :total-ms "total t (ms)"
   :s-per-sec "s/sec"
   :source-batch-size "src batch"
   :target-batch-size "tgt batch"
   :get-buffer-size "get buf"
   :statement-buffer-size "s buf"
   :batch-buffer-size "batch buf"
   :batch-timeout "batch ms"})

(def col-order
  ["statements"
   "src batch"
   "get buf"
   "s buf"
   "batch buf"
   "batch ms"
   "tgt batch"
   "total t (ms)"
   "s/sec"])

(defn- ->row
  [row-map]
  (into {}
        (map (fn [[k v]]
               [(get
                 col-headers
                 k
                 (name k))
                v]))
        row-map))

(defn run-bench [{:keys [payload-path
                         source-port
                         source-batch-size
                         target-port
                         target-batch-size
                         num-statements
                         warmup-batches
                         get-buffer-size
                         statement-buffer-size
                         batch-buffer-size
                         batch-timeout
                         print-results]
                  :or {payload-path "dev-resources/bench/payload.json"
                       source-port 8080
                       source-batch-size 50
                       target-port 8081
                       target-batch-size 50
                       num-statements 10000
                       warmup-batches 0
                       get-buffer-size 10
                       batch-timeout 200
                       print-results true}}]
  (when print-results
    (printf "\nInitializing source LRS from %s\n\n" payload-path))
  (sup/with-running [source (sup/lrs
                             :stream-path payload-path
                             :port source-port)
                     target (sup/lrs
                             :sink true
                             :port target-port)]
    (let [total-statements (- num-statements
                              (* warmup-batches target-batch-size))
          [since until] (sup/lrs-stored-range source)
          job-id (.toString (java.util.UUID/randomUUID))
          job {:id job-id,
               :config
               (cond-> {:batch-timeout batch-timeout
                        :get-buffer-size get-buffer-size
                        :source
                        {:batch-size source-batch-size
                         :request-config
                         {:url-base (format "http://0.0.0.0:%d"
                                            source-port),
                          :xapi-prefix "/xapi"},
                         :get-params
                         {:since since
                          :until until}},
                        :target
                        {:batch-size target-batch-size
                         :request-config
                         {:url-base (format "http://0.0.0.0:%d"
                                            target-port),
                          :xapi-prefix "/xapi"}}}
                 statement-buffer-size
                 (assoc :statement-buffer-size statement-buffer-size)
                 batch-buffer-size
                 (assoc :batch-buffer-size batch-buffer-size)),
               :state
               {:status :init,
                :cursor "1970-01-01T00:00:00.000000000Z",
                :source {:errors []},
                :target {:errors []},
                :errors [],
                :filter {}}}
          ;; Run the job
          all-states (a/<!! (a/into [] (:states (xapipe/run-job job))))
          _ (when (-> all-states last :state :status (= :error))
              (throw (ex-info "Job Error!"
                              {:type ::job-error
                               :state (:state (last all-states))})))

          total-ms (job-time-ms (drop warmup-batches all-states))
          s-per-sec (double
                     (* 1000
                        (/ total-statements
                           total-ms)))
          run-config (-> all-states first :config)
          row-data (merge
                    {:total-statements total-statements
                     :total-ms total-ms
                     :s-per-sec s-per-sec}
                    {:source-batch-size (-> run-config :source :batch-size)
                     :target-batch-size (-> run-config :target :batch-size)}
                    (select-keys run-config
                                 [:get-buffer-size
                                  :statement-buffer-size
                                  :batch-buffer-size
                                  :batch-timeout]))]
      (when print-results
        (pprint/print-table
         col-order
         [(->row row-data)]))
      row-data)))

(defn run-bench-matrix
  [argm]
  (pprint/print-table
   (into ["label" "runs"]
         col-order)
   (map ->row
        (map
         (fn [{:keys [label
                      num-runs
                      warmup
                      merge-args]}]
           (assert (< 0 num-runs) "Must run at least once")
           (let [run! #(run-bench
                        (merge argm
                               {:print-results false}
                               merge-args))
                 ;; Do the warmup
                 _ (do
                     (printf "%s warmup x %d " label warmup)
                     (flush))
                 _ (doseq [_ (repeatedly warmup run!)]
                     (print "|")
                     (flush))
                 _ (do (print "\n\n") (flush))
                 ;; Run the bench
                 _ (do
                     (printf "%s run x %d " label num-runs)
                     (flush))
                 runs (doall
                       (for [r (repeatedly num-runs run!)]
                         (do
                           (print "|")
                           (flush)
                           r)))
                 _ (do (print "\n\n") (flush))
                 ]
             (let [first-run (first runs)]
               (merge (dissoc first-run
                              :s-per-sec
                              :total-ms)
                      {:label label
                       :runs num-runs
                       :total-ms (maths/mean (map :total-ms runs))
                       :s-per-sec (maths/mean (map :s-per-sec runs))}))))
         [;; Default tuning args (including calculated)
          {:label "defaults"
           :num-runs 10
           :warmup 5
           :merge-args
           {:source-batch-size 50
            :target-batch-size 50
            :get-buffer-size 10
            :statement-buffer-size 500
            :batch-buffer-size 10
            :batch-timeout 200}}]))))

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
            [java-time.seqs :as tseq]))

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

(defn run-lrs
  [{:keys [payload-path]
    :or {payload-path "dev-resources/bench/payload.json"}}]
  (let [lrs (sup/lrs
             :stream-path payload-path
             :port 8080)]
    ((:start lrs))))

(defn run-bench [{:keys [payload-path
                         source-port
                         target-port
                         num-statements]
                  :or {payload-path "dev-resources/bench/payload.json"
                       source-port 8080
                       target-port 8081
                       num-statements 10000}}]
  (printf "\nInitializing source LRS from %s\n\n" payload-path)
  (sup/with-running [source (sup/lrs
                             :stream-path payload-path
                             :port source-port)
                     target (sup/lrs
                             :sink true
                             :port target-port)]
    (let [[since until] (sup/lrs-stored-range source)
          job-id (.toString (java.util.UUID/randomUUID))
          job {:id job-id,
               :config
               {:source
                {:request-config
                 {:url-base (format "http://0.0.0.0:%d"
                                    source-port),
                  :xapi-prefix "/xapi"},
                 :get-params
                 {:since since
                  :until until}},
                :target
                {:request-config
                 {:url-base (format "http://0.0.0.0:%d"
                                    target-port),
                  :xapi-prefix "/xapi"}}},
               :state
               {:status :init,
                :cursor "1970-01-01T00:00:00.000000000Z",
                :source {:errors []},
                :target {:errors []},
                :errors [],
                :filter {}}}
          t-before (System/currentTimeMillis)
          ;; Run the job
          all-states (a/<!! (a/into []
                                    (:states (xapipe/run-job job))))
          t-after (System/currentTimeMillis)
          _ (when (-> all-states last :state :status (= :error))
              (throw (ex-info "Job Error!"
                              {:type ::job-error
                               :state (:state (last all-states))})))

          total-ms (- t-after t-before)
          s-per-sec (double
                     (* 1000
                        (/ num-statements
                           total-ms)))]
      (pprint/print-table
       [{"s/sec throughput" s-per-sec}]))))

(defn run-conf-bench
  "Older bench based on running conformance results through"
  [{:keys [runs
           warmup
           seed-path
           source-port
           target-port]
    :or {runs 100
         warmup 10
         seed-path "dev-resources/lrs/after_conf.edn"
         source-port 8080
         target-port 8081}}]
  (assert (< 1 runs) "Must run at least once")
  (assert (nat-int? warmup) "Negative warmup doesn't make sense")
  (printf "\nInitializing source LRS from %s\n\n" seed-path)
  (sup/with-running [source (sup/lrs
                             :seed-path seed-path
                             :port source-port)
                     target (sup/lrs
                             :sink true
                             :port target-port)]
    (let [[since until] (sup/lrs-stored-range source)
          source-count (sup/lrs-count source)
          total-statements (* runs source-count)
          job-id (.toString (java.util.UUID/randomUUID))
          base-job {:id job-id,
                    :config
                    {:source
                     {:request-config
                      {:url-base (format "http://0.0.0.0:%d"
                                         source-port),
                       :xapi-prefix "/xapi"},
                      :get-params
                      {:since since
                       :until until}},
                     :target
                     {:request-config
                      {:xapi-prefix "/xapi"}}},
                    :state
                    {:status :init,
                     :cursor "1970-01-01T00:00:00.000000000Z",
                     :source {:errors []},
                     :target {:errors []},
                     :errors [],
                     :filter {}}}
          _ (printf "Benching with %d runs for a total of %d statements after %d run warmup...\n\n"
                    runs
                    total-statements
                    warmup)
          _ (println "Starting warmup...")
          results (drop warmup
                        (doall
                         (for [idx (range (+ warmup runs))
                               :let [_ (when (= (dec warmup) idx)
                                         (println "...warmup complete")
                                         (flush))]]
                           (let [job (assoc-in base-job
                                               [:config
                                                :target
                                                :request-config
                                                :url-base]
                                               (format "http://0.0.0.0:%d"
                                                       target-port))
                                 t-before (System/currentTimeMillis)
                                 ;; Run the job
                                 all-states (a/<!! (a/into []
                                                           (:states (xapipe/run-job job))))
                                 t-after (System/currentTimeMillis)]
                             (when (-> all-states last :state :status (= :error))
                               (throw (ex-info "Job Error!"
                                               {:type ::job-error
                                                :state (:state (last all-states))})))
                             (when (<= warmup idx)
                               (print "#")
                               (let [run-idx (- idx
                                                warmup)]
                                 (when (and (not (zero? run-idx))
                                            (zero? (rem run-idx
                                                        80)))
                                   (print "\n")))
                               (flush))

                             {:idx idx
                              :t-before t-before
                              :t-after t-after}))))
          _ (print "\n")
          ts-ms (for [{:keys [t-before
                              t-after]} results]
                  (- t-after t-before))
          [min'
           max'
           mean
           median
           stddev
           sum] ((juxt
                  #(reduce min %)
                  #(reduce max %)
                  maths/mean
                  maths/median
                  #(maths/stddev
                    %
                    :complete-population? true)
                  #(reduce + %))
                 ts-ms)]
      (pprint/print-table
       [{"min (ms)" min'
         "max (ms)" max'
         "mean (ms)" mean
         "median (ms)" median
         "stdev (ms)" stddev
         "s/sec throughput"
         (double
          (* 1000
             (/ total-statements
                sum)))}]))))

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

(defn run-lrs
  "Run an LRS that will stream out statements from a file"
  [{:keys [payload-path]
    :or {payload-path "dev-resources/bench/payload.json"}}]
  (let [lrs (sup/lrs
             :stream-path payload-path
             :port 8080)]
    ((:start lrs))))


(defn- job-time-ms
  "Derive total job time in ms from run states"
  [states]
  (-
   (-> states last :state :updated tu/parse-inst inst-ms)
   (-> states first :state :updated tu/parse-inst inst-ms)))

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
          ;; Run the job
          all-states (a/<!! (a/into [] (:states (xapipe/run-job job))))
          ;; _ (pprint/pprint {:job-states (mapv :state all-states)})
          _ (when (-> all-states last :state :status (= :error))
              (throw (ex-info "Job Error!"
                              {:type ::job-error
                               :state (:state (last all-states))})))

          total-ms (job-time-ms all-states)
          s-per-sec (double
                     (* 1000
                        (/ num-statements
                           total-ms)))]
      (pprint/print-table
       [{"statements" num-statements
         "total time (ms)" total-ms
         "s/sec throughput" s-per-sec}]))))

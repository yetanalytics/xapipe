(ns com.yetanalytics.xapipe.job.json
  "JSON Serialization/Deserialization for Jobs"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [cognitect.transit :as transit]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.config :as config]
            [xapi-schema.spec :as xs])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File]))

(s/fdef write-transit-str
  :args (s/cat :data any?)
  :ret string?)

(defn write-transit-str
  [data]
  (with-open [baos (ByteArrayOutputStream.)]
    (let [w (transit/writer baos :json)]
      (transit/write w data)
      (.toString baos "UTF-8"))))

(s/fdef read-transit-str
  :args (s/cat :t-str
               (s/with-gen string?
                 (fn []
                   (sgen/fmap
                    write-transit-str
                    (s/gen any?)))))
  :ret any?)

(defn read-transit-str
  [^String t-str]
  (with-open [bais (ByteArrayInputStream. (.getBytes t-str "UTF-8"))]
    (let [r (transit/reader bais :json)]
      (transit/read r))))

(defn- pack-paths
  "Transit encode a list of paths in data, if found"
  [data paths]
  (reduce
   (fn [d p]
     (cond-> d
       (some? (get-in d p))
       (update-in p write-transit-str)))
   data
   paths))

(defn- unpack-paths
  "Transit decode a list of paths in data, if found"
  [data paths]
  (reduce
   (fn [d p]
     (cond-> d
       (some? (get-in d p))
       (update-in p read-transit-str)))
   data
   paths))

(s/def ::job-json
  (s/with-gen
    (s/and string?
           not-empty)
    (fn []
      (sgen/fmap
       ;; job->json is identical to cheshire generate so this is OK
       ;; EXCEPT for the need to pack stuff
       (comp
        json/generate-string
        #(pack-paths % [[:state :filter :pattern]]))
       (s/gen job/job-spec)))))

(defn- keywordize-status
  [job]
  (update-in job [:state :status] (partial keyword nil)))

(defn- keywordize-error-types
  [errors]
  (mapv
   (fn [error]
     (update error :type (partial keyword nil)))
   errors))

(defn- keywordize-job-error-types
  [job]
  (-> job
      (update-in [:state :errors] keywordize-error-types)
      (update-in [:state :source :errors] keywordize-error-types)
      (update-in [:state :target :errors] keywordize-error-types)))

(s/fdef json->job
  :args (s/cat :json-str ::job-json)
  :ret any? ;; we don't spec this because the job may need an update
  )

(defn json->job
  "Parse a job from JSON."
  [^String json-str]
  (-> (json/parse-string json-str (partial keyword nil))
      keywordize-status
      keywordize-job-error-types
      (unpack-paths [[:state :filter :pattern]])
      (update :config config/ensure-defaults)))

(s/def ::pretty boolean?)

(s/fdef job->json
  :args (s/cat :job job/job-spec
               :kwargs (s/keys* :opt-un [::pretty]))
  :ret ::job-json)

(defn job->json [job & {:as kwargs}]
  (json/generate-string
   (pack-paths job [[:state :filter :pattern]])
   kwargs))

(s/fdef job->json-file!
  :args (s/cat :job job/job-spec
               :out (s/with-gen
                      (s/or :path string?
                            :file #(instance? File %))
                      (fn []
                        (sgen/return "/dev/null")))
               :kwargs (s/keys* :opt-un [::pretty]))
  :ret nil?)

(defn job->json-file!
  [job
   out
   & {:as kwargs}]
  (with-open [w (io/writer out)]
    (json/generate-stream
     (pack-paths job [[:state :filter :pattern]])
     w
     kwargs)
    nil))

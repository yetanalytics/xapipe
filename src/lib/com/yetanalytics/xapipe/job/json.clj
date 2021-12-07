(ns com.yetanalytics.xapipe.job.json
  "JSON Serialization/Deserialization for Jobs"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [cheshire.core :as json]
            [com.yetanalytics.xapipe.job :as job]
            [com.yetanalytics.xapipe.job.config :as config]))

(s/def ::job-json
  (s/with-gen
    (s/and string?
           not-empty)
    (fn []
      (sgen/fmap
       ;; job->json is identical to cheshire generate so this is OK
       json/generate-string
       (s/gen job/job-spec)))))

(defn- keywordize-status
  [job]
  (update-in job [:state :status] (partial keyword nil)))

(s/fdef json->job
  :args (s/cat :json-str ::job-json)
  :ret job/job-spec)

(defn json->job
  "Parse a job from JSON"
  [^String json-str]
  (-> (json/parse-string json-str (partial keyword nil))
      keywordize-status
      (update :config config/ensure-defaults)))

(s/fdef job->json
  :args (s/cat :job job/job-spec)
  :ret ::job-json)

(defn job->json [job]
  (json/generate-string job))

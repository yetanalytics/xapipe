(ns com.yetanalytics.xapipe
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]
            [clojure.core.async :as a]
            [com.yetanalytics.xapipe.job :as job]))

(s/def ::job
  job/job-spec)

;; TODO: delete boiler
(defn greet
  "Callable entry point to the application."
  [data]
  (println (str "Hello, " (or (:name data) "World") "!")))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (greet {:name (first args)}))

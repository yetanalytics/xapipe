(ns com.yetanalytics.xapipe.filter.path
  "Simple JsonPath-based filtering to ensure dense data."
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.pathetic :as path]
            [com.yetanalytics.pathetic.json-path :as jp]
            [xapi-schema.spec :as xs]))

(s/def ::path ::jp/path)

(s/def ::ensure-paths (s/every ::path))

(def path-filter-cfg-spec
  (s/keys :req-un [::ensure-paths]))

(s/fdef path-filter-pred
  :args (s/cat :config path-filter-cfg-spec)
  :ret (s/fspec :args (s/cat :record
                             (s/keys :req-un [::xs/statement]))
                :ret boolean?))

(defn path-filter-pred
  [{:keys [ensure-paths]}]
  (fn [{:keys [statement]}]
    (every?
     (fn [path]
       (not-empty (path/get-paths* statement [path])))
     ensure-paths)))

(ns com.yetanalytics.xapipe.filter.path
  "Simple JsonPath-based filtering to ensure dense data."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.yetanalytics.pathetic :as path]
            [com.yetanalytics.pathetic.json-path :as jp]
            [xapi-schema.spec :as xs]))

(s/def ::path ::jp/path)

(s/fdef parse-path
  :args (s/cat :path-str
               (s/with-gen string?
                 (fn []
                   (sgen/elements
                    ["$.id"
                     "$.timestamp"
                     "$.object.definition.type"
                     "$.result.duration"
                     "$.result.success"
                     "$.result.completion"
                     "$.result['success', 'completion']"
                     "$.context.contextActivities.category[*].id"
                     "$"
                     "$.foo"]))))
  :ret ::path)

(defn parse-path
  "Parse a JsonPath path, ignore any additional paths"
  [path-str]
  (first
   (path/parse-paths path-str)))

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

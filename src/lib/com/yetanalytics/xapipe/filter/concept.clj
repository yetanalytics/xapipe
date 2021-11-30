(ns com.yetanalytics.xapipe.filter.concept
  "Functions to apply concept-based filtering to statement streams."
  (:require [com.yetanalytics.persephone.template :as pt]
            [com.yetanalytics.persephone :as p]
            [clojure.pprint :refer [pprint]]))

(def mock-template
  {:id "https://xapinet.org/xapi/yet/mock/v1/template"
   :type "StatementTemplate"
   :inScheme "https://xapinet.org/xapi/yet/mock/v1"
   :prefLabel {:en "Mock Template"}
   :definition {:en "Empty Statement Template used for on-the-fly concept validation."}})

(defn t-val
  "take a map of determining properties and returns a persephone validator with
  the determining properties merged onto a mock template"
  [det-props]
  (p/template->validator (merge mock-template det-props)))

(defn verb-validators
  "takes coll of xapi verb ids and returns a list of persephone validators, one
  for each verb id"
  [verb-ids]
  (map #(t-val {:verb %}) verb-ids))

(defn activity-type-validators
  "takes a coll of xapi activity type ids and returns a list of persephone
  validators for each activity location and the activity-type-id"
  [activity-type-ids]
  (reduce (fn [validators activity-type-id]
            (into validators
                  [(t-val {:objectActivityType activity-type-id})
                   (t-val {:contextParentActivityType [activity-type-id]})
                   (t-val {:contextGroupingActivityType [activity-type-id]})
                   (t-val {:contextCategoryActivityType [activity-type-id]})
                   (t-val {:contextOtherActivityType [activity-type-id]})]))
          []
          activity-type-ids))

(defn attachment-usage-validators
  "takes a coll of xapi attachment usage types and returns a list of persephone
  validators, one for each type"
  [attachment-usage-types]
  (map #(t-val {:attachmentUsageType [%]}) attachment-usage-types))

(comment


  (clojure.pprint/pprint (activity-type-validators ["http://www.wjh.com" "http://dfiwhidhwd.ciheifhe"]))
  (clojure.pprint/pprint (verb-validators ["http://www.wjh.com" "http://dfiwhidhwd.ciheifhe"]))

  (attachment-usage-validators ["http://www.google.com"])


  (p/validate-statement-vs-template
   (verb-validator "https://xapinet.org/xapi/yet/calibration/v1/concepts#didnt")
   statement)

  (let [act-val (activity-type-validator "https://xapinet.org/xapi/stetmt/its/ActivityType#assessment.node.task")]
    (clojure.pprint/pprint (act-val statement)))

  (pprint (activity-type-validators "https://xapinet.org/xapi/stetmt/its/ActivityType#assessment.node.task"))

  )

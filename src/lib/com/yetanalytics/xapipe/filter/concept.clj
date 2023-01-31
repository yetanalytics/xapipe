(ns com.yetanalytics.xapipe.filter.concept
  "Functions to apply concept-based filtering to statement streams."
  (:require [com.yetanalytics.pan.objects.concepts.verb :as v]
            [com.yetanalytics.pan.objects.concepts.activity-type :as at]
            [com.yetanalytics.pan.objects.template :as t]
            [com.yetanalytics.pan.objects.concepts.attachment-usage-type :as aut]
            [xapi-schema.spec :as xs]
            [clojure.spec.alpha :as s]))

(def mock-template
  {:id "https://xapinet.org/xapi/yet/mock/v1/template"
   :type "StatementTemplate"
   :inScheme "https://xapinet.org/xapi/yet/mock/v1"
   :prefLabel {:en "Mock Template"}
   :definition {:en "Empty Statement Template used for on-the-fly concept validation."}})

(s/def ::predicate-fn
  (s/fspec :args (s/cat :statement
                        ::xs/statement)
           :ret  boolean?))

(s/def ::verb ::v/id)
(s/def ::objectActivityType ::at/id)
(s/def ::contextParentActivityType
  (s/every ::at/id))
(s/def ::contextGroupingActivityType
  (s/every ::at/id))
(s/def ::contextCategoryActivityType
  (s/every ::at/id))
(s/def ::contextOtherActivityType
  (s/every ::at/id))
(s/def ::attachmentUsageType
  (s/every ::aut/id))

(s/fdef make-template
  :args (s/cat :det-props
               (s/keys
                :opt-un [::verb
                         ::objectActivityType
                         ::contextParentActivityType
                         ::contextGroupingActivityType
                         ::contextCategoryActivityType
                         ::contextOtherActivityType
                         ::attachmentUsageType]))
  :ret ::t/template)

(defn make-template
  "take a map of determining properties and returns a template with
  the determining properties merged onto a mock template"
  [det-props]
  (merge mock-template det-props))

(s/def ::verb-ids
  (s/every ::verb))

(s/fdef verb-templates
  :args (s/cat :verb-ids ::verb-ids)
  :ret  (s/every ::t/template))

(defn verb-templates
  "takes coll of xapi verb ids and returns a list of persephone templates, one
  for each verb id"
  [verb-ids]
  (map #(make-template {:verb %}) verb-ids))

(s/def ::activity-type-ids
  (s/every ::at/id))

(s/fdef activity-type-templates
  :args (s/cat :activity-type-ids ::activity-type-ids)
  :ret  (s/every ::t/template))

(defn activity-type-templates
  "takes a coll of xapi activity type ids and returns a list of
  templates for each activity location and the activity-type-id"
  [activity-type-ids]
  (into []
        (mapcat
         (fn [atid]
           [(make-template {:objectActivityType atid})
            (make-template {:contextParentActivityType [atid]})
            (make-template {:contextGroupingActivityType [atid]})
            (make-template {:contextCategoryActivityType [atid]})
            (make-template {:contextOtherActivityType [atid]})])
         activity-type-ids)))

(s/def ::attachment-usage-type-ids
  (s/every ::aut/id))

(s/fdef attachment-usage-templates
  :args (s/cat :attachment-usage-types ::attachment-usage-type-ids)
  :ret  (s/every ::t/template))

(defn attachment-usage-templates
  "takes a coll of xapi attachment usage types and returns a list of
  templates, one for each type"
  [attachment-usage-types]
  (map #(make-template {:attachmentUsageType [%]}) attachment-usage-types))

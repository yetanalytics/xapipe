(ns com.yetanalytics.xapipe.filter.concept
  "Functions to apply concept-based filtering to statement streams."
  (:require [com.yetanalytics.persephone.template :as pt]
            [com.yetanalytics.persephone :as p]
            [com.yetanalytics.pan.objects.concepts.verbs :as v]
            [com.yetanalytics.pan.objects.concepts.activity-types :as at]
            [com.yetanalytics.pan.objects.template :as t]
            [com.yetanalytics.pan.objects.concepts.attachment-usage-types :as aut]
            [xapi-schema.spec :as xs]
            [clojure.spec.alpha :as s]))

(def mock-template
  {:id "https://xapinet.org/xapi/yet/mock/v1/template"
   :type "StatementTemplate"
   :inScheme "https://xapinet.org/xapi/yet/mock/v1"
   :prefLabel {:en "Mock Template"}
   :definition {:en "Empty Statement Template used for on-the-fly concept validation."}})

(s/def ::validator-fn
  (s/fspec :args (s/cat :statement
                        ::xs/statement)
           :ret  (s/nilable
                  ;; Persephone Validation Errors
                  (s/every any?))))


(s/def ::predicate-fn
  (s/fspec :args (s/cat :statement
                        ::xs/statement)
           :ret  boolean?))

(s/def ::validator
  (s/keys :req-un [::t/id
                   ::validator-fn
                   ::predicate-fn]))

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

(s/fdef t-val
  :args (s/cat :det-props
               (s/keys
                :opt-un [::verb
                         ::objectActivityType
                         ::contextParentActivityType
                         ::contextGroupingActivityType
                         ::contextCategoryActivityType
                         ::contextOtherActivityType
                         ::attachmentUsageType]))
  :ret ::validator)

(defn t-val
  "take a map of determining properties and returns a persephone validator with
  the determining properties merged onto a mock template"
  [det-props]
  (p/template->validator (merge mock-template det-props)))

(s/def ::verb-ids
  (s/every ::verb))

(s/fdef verb-validators
  :args (s/cat :verb-ids ::verb-ids)
  :ret  (s/every ::validator))

(defn verb-validators
  "takes coll of xapi verb ids and returns a list of persephone validators, one
  for each verb id"
  [verb-ids]
  (map #(t-val {:verb %}) verb-ids))

(s/def ::activity-type-ids
  (s/every ::at/id))

(s/fdef activity-type-validators
  :args (s/cat :activity-type-ids ::activity-type-ids)
  :ret  (s/every ::validator))

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

(s/def ::attachment-usage-type-ids
  (s/every ::aut/id))

(s/fdef attachment-usage-validators
  :args (s/cat :attachment-usage-types ::attachment-usage-type-ids)
  :ret  (s/every ::validator))

(defn attachment-usage-validators
  "takes a coll of xapi attachment usage types and returns a list of persephone
  validators, one for each type"
  [attachment-usage-types]
  (map #(t-val {:attachmentUsageType [%]}) attachment-usage-types))

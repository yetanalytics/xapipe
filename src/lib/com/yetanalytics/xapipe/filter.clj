(ns com.yetanalytics.xapipe.filter
  "Apply profile-based filtering to statement streams."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [xapi-schema.spec :as xs]
            [cheshire.core :as json]
            [com.yetanalytics.persephone :as per]
            [com.yetanalytics.persephone.pattern.fsm :as fsm]
            [com.yetanalytics.persephone.pattern.fsm-spec :as fsm-spec]
            [com.yetanalytics.persephone.template :as per-template]
            [com.yetanalytics.persephone.utils.json :as per-json]
            [com.yetanalytics.pan.objects.pattern :as pat]
            [com.yetanalytics.pan.objects.profile :as prof]
            [com.yetanalytics.pan.objects.template :as template]
            [com.yetanalytics.xapipe.client.multipart-mixed :as mm]
            [com.yetanalytics.xapipe.filter.path :as path]
            [com.yetanalytics.xapipe.filter.concept :as concept]))

(s/def ::profile-url string?) ;; These can be from disk, so don't spec 'em too hard
(s/def ::template-id ::template/id)

(s/fdef get-profile
  :args (s/cat :url ::profile-url)
  :ret ::prof/profile)

(defn get-profile
  "Get a profile from the specified URL or throw"
  [url]
  (try (json/parse-string-strict (slurp url) #(keyword nil %))
       (catch Exception ex
         (throw (ex-info "Profile GET error"
                         {:type ::profile-get-error
                          :url url}

                         ex)))))

;; The record we filter

(def record-spec
  (s/keys :req-un [::xs/statement
                   ::mm/attachments]))

;; A (stateless) predicate
(def filter-pred-spec
  (s/fspec :args (s/cat :record
                        record-spec)
           :ret boolean?))

(s/def ::profile-urls
  (s/every ::profile-url))

;; Concept filter fns

(s/def ::activity-type-ids ::concept/activity-type-ids)

(s/def ::verb-ids ::concept/verb-ids)

(s/def ::attachment-usage-types ::concept/attachment-usage-type-ids)

(s/def ::concept-types
  (s/every #{"Verb" "ActivityType" "AttachmentUsageType"}))

(s/def ::concept
  (s/keys :req-un [::profile-urls
                   ::concept-types
                   ::activity-type-ids
                   ::verb-ids
                   ::attachment-usage-types]))

(s/fdef concept-filter-pred
  :args (s/cat :concept-cfg ::concept)
  :ret filter-pred-spec)


(defn concept-filter-pred
  "Given config for a Concept-based filter, return a predicate
  function to filter records."
  [{:keys [profile-urls
           activity-type-ids
           verb-ids
           attachment-usage-types
           concept-types]}]
  ;; TODO: Update for new persephone APIs
  (constantly true)
  #_(let [concepts   (reduce (fn [concepts profile]
                             (into concepts (:concepts profile)))
                           [] (map get-profile profile-urls))
        vrb-ids    (or (not-empty verb-ids)
                       (map :id (filter #(= (:type %) "Verb") concepts)))
        act-ids    (or (not-empty activity-type-ids)
                       (map :id (filter #(= (:type %) "ActivityType") concepts)))
        att-ids    (or (not-empty attachment-usage-types)
                       (map :id (filter #(= (:type %) "AttachmentUsageType") concepts)))
        validators (cond-> []
                     (empty? concept-types)
                     (concat (concept/verb-validators vrb-ids)
                             (concept/activity-type-validators act-ids)
                             (concept/attachment-usage-validators att-ids))
                     (some #{"Verb"} (set concept-types))
                     (into (concept/verb-validators vrb-ids))
                     (some #{"ActivityType"} (set concept-types))
                     (into (concept/activity-type-validators act-ids))
                     (some #{"AttachmentUsageType"} (set concept-types))
                     (into (concept/attachment-usage-validators att-ids)))]
    (fn [{:keys [statement attachments]}]
      (some?
       (some (fn [v]
               (per/validate-statement-vs-template
                        v statement))
             validators)))))

;; Template filter config

(s/def ::template-ids
  (s/every ::profile-url))

(s/def ::template
  (s/keys :req-un [::profile-urls
                   ::template-ids]))

(s/fdef template-filter-pred
  :args (s/cat :template-cfg ::template)
  :ret filter-pred-spec)

(defn template-filter-pred
  "Given config for a Statement Template-based filter, return a predicate
  function to filter records."
  [{:keys [profile-urls
           template-ids]}]
  (let [validators (apply per/compile-profiles->validators
                          (map get-profile profile-urls)
                          (cond-> []
                            (not-empty template-ids)
                            (conj :selected-templates template-ids)))]
    (fn [{:keys [statement
                 attachments]}]
      (per/validate-statement validators
                              statement))))

(s/def ::pattern-id
  (s/with-gen ::pat/id
    (fn []
      ;; TODO: figure out gen issue with ::pat/id
      (s/gen ::xs/iri))))

(def pattern-filter-state-spec
  (s/or :init #{{}}
        :running
        (s/with-gen per/state-info-map-spec
          (fn []
            (s/gen per/state-info-map-spec
                   ;; TODO: gen error for pan strings
                   {:com.yetanalytics.pan.axioms/string
                    (fn [] (s/gen ::xs/iri))})))))

(s/fdef evict-keys
  :args (s/cat :states-map ::per/states-map
               :state-keys (s/every
                            (s/tuple per/registration-key-spec
                                     ::pattern-id)))
  :ret ::per/states-map)

(defn evict-keys
  [states-map state-keys]
  (reduce
   (fn [sm [sk pid]]
     (let [wo (update sm sk dissoc pid)]
       (cond-> wo
         (empty? (get wo sk))
         (dissoc sk))))
   states-map
   state-keys))

;; A stateful predicate for pattern filters
(def pattern-filter-pred-spec
  (s/fspec :args (s/cat :state pattern-filter-state-spec
                        :record record-spec)
           :ret [pattern-filter-state-spec boolean?]))

(s/def ::pattern-ids (s/every ::pattern-id))

;; Pattern filter config
(s/def ::pattern
  (s/keys :req-un [::profile-urls
                   ::pattern-ids]))

(s/fdef pattern-filter-pred
  :args (s/cat :pattern-cfg ::pattern)
  :ret pattern-filter-pred-spec)

(defn pattern-filter-pred
  "Build a stateful predicate that matches profile patterns as far as it can."
  [{:keys [profile-urls
           pattern-ids]}]
  (let [fsm-map (apply
                 per/compile-profiles->fsms
                 (map get-profile profile-urls)
                 (cond-> []
                   (not-empty pattern-ids)
                   (conj :selected-patterns pattern-ids)))]
    (fn [state
         {:keys [statement]
          :as record}]
      ;; Simple optimization: If there is a registration, we try matching
      (if (get-in statement ["context" "registration"])
        (let [{:keys [accepts
                      rejects
                      states-map
                      error] :as ret} (per/match-statement
                                       fsm-map state statement)]
          (if error
            [state false]
            (let [;; Remove any accepted/rejected from the state map
                  ;; to save space!
                  states-map' (evict-keys
                               states-map
                               (concat
                                accepts
                                rejects))]
              [;; New State
               (assoc ret
                      :states-map
                      states-map')
               ;; Predicate Result
               (some?
                (or
                 ;; On any accept
                 (not-empty accepts)
                 ;; No news is good news
                 (empty? rejects)
                 ;; If rejects only, we need to check for open patterns
                 ;; after eviction since this must satisfy at least one
                 (some
                  ;; State keys could vary for subreg
                  ;; We are checking for at least one still going
                  (fn [[state-key _]]
                    (get states-map' state-key))
                  rejects)))])))
        ;; No registration, immediate return
        [state false]))))

(s/def ::path path/path-filter-cfg-spec)

;; Config map for all filtering
(def filter-config-spec
  (s/keys :opt-un [::template
                   ::pattern
                   ::concept]))

(s/def :com.yetanalytics.xapipe.filter.stateless-predicates/template
  filter-pred-spec)
(s/def :com.yetanalytics.xapipe.filter.stateless-predicates/path
  filter-pred-spec)
(s/def :com.yetanalytics.xapipe.filter.stateless-predicates/concept
  filter-pred-spec)

(s/fdef stateless-predicates
  :args (s/cat :config filter-config-spec)
  :ret (s/keys :opt-un
               [:com.yetanalytics.xapipe.filter.stateless-predicates/template
                :com.yetanalytics.xapipe.filter.stateless-predicates/path
                :com.yetanalytics.xapipe.filter.stateless-predicates/concept]))

(defn stateless-predicates
  "Stateless predicates are simple true/false"
  [{:keys [template
           path
           concept]}]
  (cond-> {}
    template (assoc :template (template-filter-pred template))
    path     (assoc :path (path/path-filter-pred path))
    concept  (assoc :concept (concept-filter-pred concept))))

(s/def :com.yetanalytics.xapipe.filter.stateful-predicates/pattern
  pattern-filter-pred-spec)

(s/fdef stateful-predicates
  :args (s/cat :config filter-config-spec)
  :ret (s/keys :opt-un
               [:com.yetanalytics.xapipe.filter.stateful-predicates/pattern]))

(defn stateful-predicates
  "Stateful predicates require state to reduce over"
  [{:keys [pattern]}]
  (cond-> {}
    pattern (assoc :pattern (pattern-filter-pred pattern))))

;; Job State, when emitted
(s/def :com.yetanalytics.xapipe.filter.state/pattern
  pattern-filter-state-spec)

(def filter-state-spec
  (s/keys :opt-un [:com.yetanalytics.xapipe.filter.state/pattern]))

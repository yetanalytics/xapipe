(ns com.yetanalytics.xapipe.filter
  "Apply profile-based filtering to statement streams."
  (:require [clojure.spec.alpha :as s]
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
  (try (per-json/json->edn (slurp url) :keywordize? true)
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
  (let [concepts   (reduce (fn [concepts profile]
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
  (let [validators
        (into []
              (for [{:keys [templates]} (map get-profile profile-urls)
                    {:keys [id] :as template} templates
                    :when (or (empty? template-ids)
                              (some (partial = id)
                                    template-ids))]
                (per/template->validator template)))]
    (fn [{:keys [statement
                 attachments]}]
      (some?
       (some (fn [v]
               (per/validate-statement-vs-template
                v statement))
             validators)))))

(s/def ::pattern-id ::pat/id)

(def state-key-spec
  (s/or :registration :context/registration
        :subreg (s/tuple :context/registration
                         :context/registration)))

(s/fdef get-state-key
  :args (s/cat :statement ::xs/statement)
  :ret (s/nilable state-key-spec))

(defn get-state-key
  "Given a statement, return a state key if possible, or nil"
  [statement]
  (let [?reg (get-in statement ["context" "registration"])
        ?subreg (get-in statement ["context" "extensions" per/subreg-iri])]
    (cond
      (and ?reg ?subreg) [?reg ?subreg]
      ?reg               ?reg
      :else              nil)))

(s/def ::accepted? boolean?)
(s/def ::states (s/coll-of int?
                           :kind set?
                           :into #{}))

(def state-info-spec
  (s/keys :req-un [::accepted?
                   ::states]))

(def pattern-filter-state-spec
  (s/map-of
   state-key-spec
   (s/map-of
    ::pattern-id
    state-info-spec)))

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
  "Given a list of profile URLs generate a stateful predicate to filter
  to only statements in patterns. If pattern ids are provided, limit filtered
  statements to those."
  [{:keys [profile-urls
           pattern-ids]}]
  (let [fsm-map (-> profile-urls
                    (->> (map get-profile)
                         (map per/profile->fsms)
                         (into {}))
                    (cond->
                        (not-empty pattern-ids)
                      (select-keys pattern-ids)))]
    (fn [state
         {:keys [statement]
          :as record}]
      (if-let [state-key (get-state-key statement)]
        (let [;; Just the state concerned with this reg
              reg-state (get state state-key)

              [new-reg-state
               accepted-patterns]
              (reduce
               (fn [[reg-s accepted] [pat-key new-s]]
                 (let [accepted? (:accepted? new-s)
                       failed? (empty? (:states new-s))]
                   [;; If the state is accepted or faled.
                    ;; remove it
                    (if (or accepted?
                            failed?)
                      (dissoc reg-s pat-key)
                      ;; Otherwise, it is in-process
                      (assoc reg-s pat-key new-s))
                    ;; Track accepted for filter
                    (cond-> accepted
                      accepted?
                      (conj pat-key))]))
               [(or reg-state {})
                #{}]
               (for [[pat-key fsm] fsm-map]
                 [pat-key
                  (fsm/read-next
                   fsm
                   (get reg-state pat-key)
                   statement)]))]
          [;; Add the new in-process state or remove it
           (if (empty? new-reg-state)
             (dissoc state state-key)
             (assoc state state-key new-reg-state))
           ;; If we have in-process or are accepting, pass it
           (if (or (not-empty new-reg-state)
                   (not-empty accepted-patterns))
             true
             false)])
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

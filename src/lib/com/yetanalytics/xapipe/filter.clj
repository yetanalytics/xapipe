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
            [com.yetanalytics.xapipe.client.multipart-mixed :as mm]))

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
  (s/every ::profile-url
           :min-count 1))

(s/def ::template-ids
  (s/every ::profile-url))

;; Template filter config
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
      (some (fn [v]
              (per/validate-statement-vs-template
               v statement))
            validators))))

;; TODO: remove transducers + cleanup when proper pred filter established
(defn- with-cleanup
  "Blocking cleanup function, must be run on dropped statements"
  [pred-result attachments]
  (when (and (not pred-result) (not-empty attachments))
    (mm/clean-tempfiles! attachments))
  pred-result)

(s/fdef template-filter-xf
  :args (s/cat :template-cfg ::template)
  ;; Ret here is a transducer, TODO: spec it?
  )

(defn template-filter-xf
  "Return a transducer that will filter a sequence of statements to only those
  in the given profiles and template-ids, if provided."
  [template-cfg]
  (let [pred (template-filter-pred template-cfg)]
    (filter
     (fn [{:keys [attachments]
           :as record}]
       (with-cleanup
         (pred record)
         attachments)))))

;; TODO: end remove

(s/def ::pattern-id ::pat/id)

(def state-key-spec
  (s/or :registration :statement/registration
        :subreg (s/tuple :statement/registration
                         :statement/registration)))

(s/fdef get-state-key
  :args (s/keys :statement ::xs/statement)
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

;; TODO: remove xducers

(s/fdef pattern-filter-xf
  :args (s/cat :pattern-cfg ::pattern
               :fsm-state (s/nilable any?)
               )
  ;; Ret here is a transducer, TODO: spec it?
  )

(defn pattern-filter-xf
  "Return a transducer that will filter a sequence of statements to only those
  the given profiles' patterns, restricted to pattern-ids if provided."
  [pattern-cfg
   & [fsm-state]
   ]
  (let [pred (pattern-filter-pred pattern-cfg)]
    (fn [xf]
      (let [fsm-state-v (volatile! fsm-state)]
        (fn
          ([] (xf))
          ([result]
           (xf result))
          ([result input]
           (let [[next-state keep?] (pred @fsm-state-v input)]
             (vreset! fsm-state-v
                      next-state)
             (if keep?
               (xf result input)
               (do
                 ;; Drop the input. We must delete any attachments
                 (when-let [attachments (not-empty (:attachments input))]
                   (mm/clean-tempfiles! attachments))
                 result)))))))))

;; Config map for all filtering
(def filter-config-spec
  (s/keys :opt-un [::template]))

(s/fdef filter-xf
  :args (s/cat :config filter-config-spec)
  ;; TODO: Ret is a transducer, research specs for those
  )

(defn filter-xf
  [{:keys [template]}]
  (apply comp
         (cond-> []
           template
           (conj (template-filter-xf
                  template)))))

;; TODO: end remove

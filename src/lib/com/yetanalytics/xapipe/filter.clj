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

(s/def ::profile-urls
  (s/every ::profile-url
           :min-count 1))

(s/def ::template-ids
  (s/every ::profile-url))

;; Template filter config
(s/def ::template
  (s/keys :req-un [::profile-urls
                   ::template-ids]))

(defn- with-cleanup
  "Blocking cleanup function, must be run on dropped statements"
  [pred-result attachments]
  (when (and (not pred-result) (not-empty attachments))
    (mm/clean-tempfiles! attachments))
  pred-result)

(s/fdef template-filter-xf
  :args (s/cat :template ::template)
  ;; Ret here is a transducer, TODO: spec it?
  )

(defn template-filter-xf
  "Return a transducer that will filter a sequence of statements to only those
  in the given profiles and template-ids, if provided."
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
    (filter
     (fn [{:keys [statement
                  attachments]}]
       (with-cleanup
         (some (fn [v]
                 (per/validate-statement-vs-template
                  v statement))
               validators)
         attachments)))))

(s/def ::pattern-id ::pat/id)

(s/def ::pattern-ids (s/every ::pattern-id))

;; Pattern filter config
(s/def ::pattern
  (s/keys :req-un [::profile-urls
                   ::pattern-ids]))

(s/fdef pattern-filter-xf
  :args (s/cat :pattern ::pattern
               :fsm-state (s/nilable any?)
               )
  ;; Ret here is a transducer, TODO: spec it?
  )

(defn pattern-filter-xf
  "Return a transducer that will filter a sequence of statements to only those
  the given profiles' patterns, restricted to pattern-ids if provided."
  [{:keys [profile-urls
           pattern-ids]}
   & [fsm-state]
   ]
  (let [fsm-map (-> profile-urls
                    (->> (map get-profile)
                         (map per/profile->fsms)
                         (into {}))
                    (cond->
                        (not-empty pattern-ids)
                      (select-keys pattern-ids)))]
    (fn [xf]
      (let [fsm-state-v (volatile! fsm-state)]
        (fn
          ([] (xf))
          ([result]
           (xf result))
          ([result input]
           (let [last-state @fsm-state-v
                 ?reg (get-in input [:statement "context" "registration"])
                 ?subreg (get-in input [:statement "context" "extensions" per/subreg-iri])
                 ?state-key (cond
                              (and ?reg ?subreg) [?reg ?subreg]
                              ?reg ?reg
                              :else nil)
                 hits (into []
                            (when ?state-key
                              (for [[pat-key fsm] fsm-map
                                    :let [{:keys [accepted?]
                                           :as hit} (fsm/read-next fsm
                                                                   (get last-state ?state-key)
                                                                   (:statement input))]
                                    :when accepted?]
                                [?state-key pat-key hit])))]
             ;; TODO: How are registrations removed from this?
             ;; What is final completion?
             (let [new-state (cond-> (reduce
                                      (fn [m [state-key pat-key state]]
                                        (assoc-in m [state-key pat-key] state))
                                      last-state
                                      hits)
                               ;; remove any active if we've failed
                               (and (empty? hits) ?state-key)
                               (dissoc ?state-key))]
               (vreset! fsm-state-v
                        new-state)
               (if (not-empty hits)
                 (xf result (assoc input
                                   :fsm-state new-state))
                 (do
                   ;; Drop the input. We must delete any attachments
                   (when-let [attachments (not-empty (:attachments input))]
                     (mm/clean-tempfiles! attachments))
                   result))))))))))

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

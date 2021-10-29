(ns com.yetanalytics.xapipe.filter
  "Apply profile-based filtering to statement streams."
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [cheshire.core :as json]
            [com.yetanalytics.persephone :as per]
            [com.yetanalytics.persephone.utils.json :as per-json]
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

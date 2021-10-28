(ns com.yetanalytics.xapipe.filter
  "Apply profile-based filtering to statement streams."
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [cheshire.core :as json]
            [com.yetanalytics.persephone :as per]
            [com.yetanalytics.persephone.utils.json :as per-json]
            [com.yetanalytics.pan.objects.profile :as prof]))

(s/fdef get-profile
  :args (s/cat :url string?)
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

(s/fdef template-filter-xf
  :args (s/cat :profile-urls (s/every string?)
               :template-ids (s/every string?))
  ;; Ret here is a transducer, TODO: spec it?
  )

(defn template-filter-xf
  "Return a transducer that will filter a sequence of statements to only those
  in the given profiles and template-ids, if provided."
  [profile-urls
   template-ids]
  (let [validators
        (into []
              (for [{:keys [templates]} (map get-profile profile-urls)
                    {:keys [id] :as template} templates
                    :when (or (empty? template-ids)
                              (contains? template-ids id))]
                (per/template->validator template)))]
    (filter
     (fn [statement]
       (some (fn [v]
               (per/validate-statement-vs-template
                v statement))
             validators)))))

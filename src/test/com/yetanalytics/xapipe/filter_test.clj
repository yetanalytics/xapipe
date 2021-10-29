(ns com.yetanalytics.xapipe.filter-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.filter :refer :all]
            [com.yetanalytics.xapipe.test-support :as support]
            [clojure.set :as cset]))

(deftest template-filter-xf-test
  (let [a-profile "dev-resources/profiles/calibration_a.jsonld"
        a-template-ids (-> a-profile get-profile :templates (->> (mapv :id)))
        a-statements (into []
                           (support/gen-statements
                            50 :profiles [a-profile]))

        b-profile "dev-resources/profiles/calibration_b.jsonld"
        b-template-ids (-> b-profile get-profile :templates (->> (mapv :id)))
        b-statements (into []
                           (support/gen-statements
                            50 :profiles [b-profile]))
        all-statements (interleave a-statements b-statements)]
    (testing "Profile template filter filters by profile + template IDs"
      (are [profile-urls template-ids statements]

          (= statements
             (map :statement
                  (sequence (template-filter-xf
                             {:profile-urls profile-urls
                              :template-ids template-ids})
                            (mapv
                             (fn [s]
                               {:statement s
                                :attachments []})
                             all-statements))))

        ;; By profiles only
        ;; Just A
        [a-profile] [] a-statements
        ;; Just B
        [b-profile] [] b-statements
        ;; Both
        [a-profile b-profile] [] all-statements
        ;; None
        [] [] []
        ;; All A template IDs
        [a-profile] a-template-ids a-statements
        ;; All B template IDs
        [b-profile] b-template-ids b-statements
        ;; All template IDs
        [a-profile b-profile] (concat a-template-ids
                                      b-template-ids) all-statements))
    (testing "Filtering by single or multiple template IDs"
      (are [profile-urls n-templates template-ids statements]
          ;; For a given grouping of IDs, the resulting statements
          ;; are a subset of the given profile
          (every?
           (fn [ids]
             (cset/subset?
              (into #{}
                    (comp
                     (template-filter-xf
                      {:profile-urls profile-urls
                       :template-ids ids})
                     (map :statement))
                    (mapv
                     (fn [s]
                       {:statement s
                        :attachments []})
                     all-statements))
              (set statements)))
           (partition-all n-templates template-ids))

        [a-profile] 1 a-template-ids a-statements
        [a-profile] 5 a-template-ids a-statements
        [b-profile] 1 b-template-ids b-statements
        [b-profile] 5 b-template-ids b-statements))))

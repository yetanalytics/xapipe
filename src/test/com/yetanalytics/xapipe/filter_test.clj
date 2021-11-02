(ns com.yetanalytics.xapipe.filter-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.filter :refer :all]
            [com.yetanalytics.xapipe.test-support :as support]
            [clojure.set :as cset]))


(deftest template-filter-xf-test
  (let [;; Turn into a transducer to show sequence behavior
        template-filter-xf (fn [cfg]
                             (let [pred (template-filter-pred
                                         cfg)]
                               (filter
                                pred)))

        a-profile "dev-resources/profiles/calibration_a.jsonld"
        a-template-ids (-> a-profile get-profile :templates (->> (mapv :id)))
        a-statements (into []
                           (support/gen-statements
                            50
                            :profiles [a-profile]
                            :parameters {:seed 42}))

        b-profile "dev-resources/profiles/calibration_b.jsonld"
        b-template-ids (-> b-profile get-profile :templates (->> (mapv :id)))
        b-statements (into []
                           (support/gen-statements
                            50 :profiles [b-profile]
                            :parameters {:seed 24}))
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

(deftest pattern-filter-pred-test
  (let [profile-url "dev-resources/profiles/calibration_strict_pattern.jsonld"
        ;; This strict pattern expects activities 1, 2 and 3, in order
        [a b c] (support/gen-statements
                 3
                 :profiles [profile-url]
                 :personae [{:name "Test Subjects",
                             :objectType "Group",
                             :member
                             [{:name "alice",
                               :mbox "mailto:alice@example.org",
                               :objectType "Agent"}]}])
        pred (pattern-filter-pred
              {:profile-urls [profile-url]
               :pattern-ids []})]
    (are [statements states]
        (= states
           (rest
            (reductions
             (fn [[state _] s]
               (pred state {:statement s}))
             [{} nil]
             statements)))
      [a b c] [;; a starts the pattern
               [{"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  {:states #{1}, :accepted? false}}}
                true]
               ;; b continues
               [{"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  {:states #{0}, :accepted? false}}}
                true]
               ;; c is accepted and terminates
               [{} true]]

      [b a c] [;; a drops
               [{} false]
               ;; a picks up
               [{"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  {:states #{1}, :accepted? false}}}
                true]
               ;; c drops
               [{} false]])))

(ns com.yetanalytics.xapipe.filter-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.filter :refer :all]
            [com.yetanalytics.xapipe.test-support :as sup]
            [clojure.set :as cset]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [com.yetanalytics.pan.objects.profile :as prof]))

;; We restrict instrumentation here because we don't want to test the libs
(use-fixtures
  :once
  (sup/instrument-fixture
   (concat
    (st/enumerate-namespace 'com.yetanalytics.xapipe.filter)
    (st/enumerate-namespace 'com.yetanalytics.persephone.pattern.fsm))))

(deftest get-profile-test
  (testing "slurps the profile from wherever"
    (testing "a local file"
      (is
       (s/valid? ::prof/profile
                 (get-profile "dev-resources/profiles/calibration.jsonld"))))
    (testing "a remote file via https"
      (is
       (s/valid? ::prof/profile
                 (get-profile "https://raw.githubusercontent.com/yetanalytics/xapipe/925a16340a1c7f568b98b6d39d88d2e446ea87d5/dev-resources/profiles/calibration.jsonld"))))))

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
                           (sup/gen-statements
                            50
                            :profiles [a-profile]
                            :parameters {:seed 42}))

        b-profile "dev-resources/profiles/calibration_b.jsonld"
        b-template-ids (-> b-profile get-profile :templates (->> (mapv :id)))
        b-statements (into []
                           (sup/gen-statements
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

(deftest concept-filter-test
  (let [concept-filter-xf (fn [cfg]
                            (let [pred (concept-filter-pred
                                        cfg)]
                              (filter
                               pred)))
        ;; Profile emits a single sequence of 9 statements in a single
        ;; primary pattern: Two verbs, two object activity types, 4 context
        ;; activity types and an attachment usage type
        conc-profile "dev-resources/profiles/calibration_concept.jsonld"
        conc-stmt    (into []
                           (sup/gen-statements
                            9
                            :profiles [conc-profile]
                            :parameters {:seed 42}
                            :personae [{:name "rgregerg",
                                        :objectType "Group",
                                        :member [{:name "rolf",
                                                  :mbox "mailto:rolf@example.org"}]}]))
        diff-profile "dev-resources/profiles/calibration_a.jsonld"
        diff-stmt    (into []
                           (sup/gen-statements
                            9
                            :profiles [diff-profile]
                            :parameters {:seed 42}))]
    (testing "All Statements Pass when profile-urls but no concept-type or
ids are provided, and all fail when not containing any concepts from the profile"
      (are [profile-urls passed-num statements]
          (= passed-num
             (count (sequence (concept-filter-xf
                               {:profile-urls profile-urls
                                :concept-types []
                                :activity-type-ids []
                                :verb-ids []
                                :attachment-usage-types []})
                              (mapv
                               (fn [s]
                                 {:statement s
                                  :attachments []})
                               statements))))
        ;; Pass all profile statements
        [conc-profile] 9 conc-stmt
        ;; Fail all non-profile statements
        [conc-profile] 0 diff-stmt))
    (testing "Filtering by content types, but no specific ids"
      (are [profile-urls concept-types passed-num statements]
          (= passed-num
             (count (sequence (concept-filter-xf
                               {:profile-urls profile-urls
                                :concept-types concept-types
                                :activity-type-ids []
                                :verb-ids []
                                :attachment-usage-types []})
                              (mapv
                               (fn [s]
                                 {:statement s
                                  :attachments []})
                               statements))))

        ;; All Statements have one of the two Verbs
        [conc-profile] ["Verb"] 9 conc-stmt
        ;; All Statements have one of the three activity types
        [conc-profile] ["ActivityType"] 9 conc-stmt
        ;; Only one statement has the Attachment Usage Type
        [conc-profile] ["AttachmentUsageType"] 1 conc-stmt))
    (testing "Filtering by specific verb ids"
      (are [verb-ids concept-types passed-num statements]
          (= passed-num
             (count (sequence (concept-filter-xf
                               {:verb-ids verb-ids
                                :concept-types concept-types
                                :profile-urls []
                                :activity-type-ids []
                                :attachment-usage-types []})
                              (mapv
                               (fn [s]
                                 {:statement s
                                  :attachments []})
                               statements))))

        ;; All but one statement has verb-1
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#verb-1"]
        [] 8 conc-stmt
        ;; One statement has verb-2
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#verb-2"]
        [] 1 conc-stmt
        ;; No statements have verb-3
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#verb-3"]
        [] 0 conc-stmt
        ;; No statements should pass if concept-types is not empty but
        ;; doesn't contain Verb
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#verb-2"]
        ["ActivityType"] 0 conc-stmt))
     (testing "Filtering by specific activity type ids"
      (are [activity-type-ids concept-types passed-num statements]
          (= passed-num
             (count (sequence (concept-filter-xf
                               {:profile-urls []
                                :activity-type-ids activity-type-ids
                                :concept-types concept-types
                                :verb-ids []
                                :attachment-usage-types []})
                              (mapv
                               (fn [s]
                                 {:statement s
                                  :attachments []})
                               statements))))

        ;; 8 statements have type 1 as the object's type
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#activity-type-1"]
        [] 8 conc-stmt
        ;; One statement has type 2
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#activity-type-2"]
        [] 1 conc-stmt
        ;; 4 Statements contain type 3 (this also tests all context activity matching)
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#activity-type-3"]
        [] 4 conc-stmt
        ;; No statements should pass if concept-types is not empty but
        ;; doesn't contain ActivityType
        ["https://xapinet.org/xapi/yet/calibration-concept/v1/concepts#activity-type-1"]
        ["Verb"] 0 conc-stmt))))

(deftest pattern-filter-pred-test
  (let [profile-url "dev-resources/profiles/calibration_strict_pattern.jsonld"
        profile-url-alt "dev-resources/profiles/calibration_strict_pattern_alt.jsonld"
        ;; This strict pattern expects activities 1, 2 and 3, in order
        [a b c] (sup/gen-statements
                 3
                 :parameters {:seed 42}
                 :profiles [profile-url]
                 :personae [{:name "Test Subjects",
                             :objectType "Group",
                             :member
                             [{:name "alice",
                               :mbox "mailto:alice@example.org",
                               :objectType "Agent"}]}])
        [d e f] (sup/gen-statements
                 3
                 :parameters {:seed 43}
                 :profiles [profile-url-alt]
                 :personae [{:name "Test Subjects",
                             :objectType "Group",
                             :member
                             [{:name "bob",
                               :mbox "mailto:alice@example.org",
                               :objectType "Agent"}]}])]
    (sup/art [testing-tag pred-config statements states]
             (testing testing-tag
               (let [pred (pattern-filter-pred
                           pred-config)
                     states' (rest
                              (reductions
                               (fn [[state _] s]
                                 (pred state {:statement s}))
                               [{} nil]
                               statements))]
                 (is
                  (= states
                     states'))))

             "In order, is matched"
             {:profile-urls [profile-url]
              :pattern-ids []}
             [a b c a]
             [;; a starts
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 0, :accepted? false}}}}}
               true]
              ;; b continues
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 3, :accepted? false}}}}}
               true]
              ;; c accepts + terminates
              [{:accepts
                [["d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                  "https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"]],
                :rejects [],
                :states-map {}}
               true]
              ;; a again starts again
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 0, :accepted? false}}}}}
               true]]

             "Out of order, drop match drop"
             {:profile-urls [profile-url]
              :pattern-ids []}
             [b a c]
             [;; b drops
              [{:accepts [],
                :rejects
                [["d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                  "https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"]],
                :states-map {}}
               false]
              ;; a picks up
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 0, :accepted? false}}}}}
               true]
              ;; c drops
              [{:accepts [],
                :rejects
                [["d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                  "https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"]],
                :states-map {}}
               false]]

             "Multiple Profiles"
             {:profile-urls [profile-url
                             profile-url-alt]
              :pattern-ids []}
             [a d b e c f]
             [;; a starts a pattern
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 0, :accepted? false}}}}}
               true]
              ;; d starts another pattern
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 0, :accepted? false}}},
                 "f851859f-b0fe-4b36-9939-4276b96d302d"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern_alt/v1/patterns#pattern-1"
                  #{{:state 0, :accepted? false}}}}}
               true]
              ;; b continues first pattern
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 3, :accepted? false}}},
                 "f851859f-b0fe-4b36-9939-4276b96d302d"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern_alt/v1/patterns#pattern-1"
                  #{{:state 0, :accepted? false}}}}}
               true]
              ;; e continues second
              [{:accepts [],
                :rejects [],
                :states-map
                {"d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"
                  #{{:state 3, :accepted? false}}},
                 "f851859f-b0fe-4b36-9939-4276b96d302d"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern_alt/v1/patterns#pattern-1"
                  #{{:state 3, :accepted? false}}}}}
               true]
              ;; c accepts + terminates
              [{:accepts
                [["d7acfddb-f4c2-49f4-a081-ad1fb8490448"
                  "https://xapinet.org/xapi/yet/calibration_strict_pattern/v1/patterns#pattern-1"]],
                :rejects [],
                :states-map
                {"f851859f-b0fe-4b36-9939-4276b96d302d"
                 {"https://xapinet.org/xapi/yet/calibration_strict_pattern_alt/v1/patterns#pattern-1"
                  #{{:state 3, :accepted? false}}}}}
               true]
              ;; d accepts + terminates
              [{:accepts
                [["f851859f-b0fe-4b36-9939-4276b96d302d"
                  "https://xapinet.org/xapi/yet/calibration_strict_pattern_alt/v1/patterns#pattern-1"]],
                :rejects [],
                :states-map {}}
               true]])))

(deftest stateless-predicates-test
  (testing "transforms config into stateless predicates"
    (is (s/valid?
         (s/keys :opt-un
                 [:com.yetanalytics.xapipe.filter.stateless-predicates/template])
         (stateless-predicates
          {:template {:profile-urls ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
                      :template-ids []}})))))

(deftest stateful-predicates-test
  (testing "transforms config into stateful predicates"
    ;; Cannot test this pred because of gen failure in PAN
    (is (function?
         (:pattern
          (stateful-predicates
          {:pattern
           {:profile-urls ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
            :pattern-ids []}}))))))

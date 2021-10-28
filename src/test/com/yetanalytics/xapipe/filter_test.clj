(ns com.yetanalytics.xapipe.filter-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.filter :refer :all]
            [com.yetanalytics.xapipe.test-support :as support]))

(deftest template-filter-xf-test
  (let [a-profile "dev-resources/profiles/calibration.jsonld"
        a-statements (support/gen-statements
                      5 :profiles [a-profile])

        b-profile "dev-resources/profiles/tccc.jsonld"
        b-statements (support/gen-statements
                      5 :profiles [b-profile])
        all-statements (interleave a-statements b-statements)]
    (testing "Profile template filter filters by profile"
      (is (= a-statements
             (sequence (template-filter-xf
                        [a-profile]
                        [])
                       all-statements)))
      (is (= b-statements
             (sequence (template-filter-xf
                        [b-profile]
                        [])
                       all-statements))))))

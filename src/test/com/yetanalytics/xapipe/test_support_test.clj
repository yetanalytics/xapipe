(ns com.yetanalytics.xapipe.test-support-test
  (:require [clojure.test :refer :all]
            [com.yetanalytics.xapipe.test-support :refer :all]))

(deftest gen-statements-test
  (testing "is safe and deterministic"
    (is
     (= {true 100}
        (frequencies
         (repeatedly
          100
          (fn []
            (let [a (gen-statements
                     100
                     :profiles
                     ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
                     #_#_:parameters {:seed 42})
                  b (gen-statements
                     100
                     :profiles
                     ["dev-resources/profiles/calibration_strict_pattern.jsonld"]
                     #_#_:parameters {:seed 42})]
              (= a b)))))))))

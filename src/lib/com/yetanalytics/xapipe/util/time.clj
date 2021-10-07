(ns xapipe.util.time
  "Time(stamp) utilities"
  (:require [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs])
  (:import [java.time Instant]))

(s/fdef parse-inst
  :args (s/cat :timestamp ::xs/timestamp)
  :ret #(instance? Instant %))

;; TODO: This is primitive, will probably need timestamp norm stuff from LRS
(defn parse-inst
  ^Instant [^String timestamp]
  (Instant/parse timestamp))

;; TODO: also naive, replace
(defn normalize-stamp
  ^String [^String timestamp]
  (.toString (parse-inst timestamp)))

(s/fdef stamp-cmp
  :args (s/cat :a string?
               :b string?)
  :ret #{-1 0 1})

(defn stamp-cmp
  [a b]
  (compare (parse-inst a)
           (parse-inst b)))

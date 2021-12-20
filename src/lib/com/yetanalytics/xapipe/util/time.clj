(ns com.yetanalytics.xapipe.util.time
  "Time(stamp) utilities.
  Normalization follows process from com.yetanalytics.lrs.xapi.statements.timestamp"
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [xapi-schema.spec :as xs])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]))

(defonce ^ZoneId UTC
  (ZoneId/of "UTC"))

(defonce ^DateTimeFormatter in-formatter
  DateTimeFormatter/ISO_DATE_TIME)

(defonce ^DateTimeFormatter out-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'"))

(def instant-spec
  (s/with-gen #(instance? Instant %)
    (fn []
      (sgen/fmap
       #(Instant/ofEpochMilli ^Instant %)
       (sgen/large-integer*
        {:min 0 :max 4102444800000})))))

(s/fdef parse-inst
  :args (s/cat :timestamp ::xs/timestamp)
  :ret instant-spec)

(defn parse-inst
  ^Instant [^String timestamp]
  #_(Instant/parse timestamp)
  (-> (.parse in-formatter timestamp)
      (Instant/from)
      (.atZone UTC)
      .toInstant))

(s/fdef normalize-inst
  :args (s/cat :inst instant-spec)
  :ret ::xs/timestamp
  :fn (fn [{stamp-after :ret}]
        (= 30 (count stamp-after))))

(defn normalize-inst
  "Normalize an inst object, ensuring that it is a static length (nano), and
   UTC."
  [^Instant inst]
  (-> inst
      (Instant/from)
      (.atZone UTC)
      (->> (.format out-formatter))))

(s/fdef parse-stamp
  :args (s/cat :timestamp ::xs/timestamp)
  :ret (s/tuple string? string? (s/nilable string?) string?))

(defn parse-stamp
  "return a vector of [whole-stamp body ?frac-secs offset-or-Z]"
  [timestamp]
  (re-find
   #"^(\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)$"
   timestamp))

(s/def ::normalized-stamp
  (s/with-gen
    (s/and ::xs/timestamp
           #(.endsWith ^String % "Z")
           #(= 30 (.length ^String %)))
    (fn []
      (sgen/fmap
       normalize-inst
       (s/gen instant-spec)))))

(s/fdef normalize-stamp
  :args (s/cat :timestamp ::xs/timestamp)
  :ret ::normalized-stamp)

;; TODO: also naive, replace
(defn normalize-stamp
  ^String [^String timestamp]
  #_(.toString (parse-inst timestamp))
  (let [zulu? (.endsWith timestamp "Z")
        char-count (count timestamp)]
    (cond
      ;; We can easily detect a stamp already normalized to 8601 zulu with nano
      ;; precision, and these we can let through.
      (and zulu?
           (= 30 char-count))
      timestamp

      ;; if it has more than nano precision
      (and zulu?
           (< 30 char-count))
      (format "%sZ" (subs timestamp 0 29))

      ;; we have some kind of offset. We need to parse and re-add the frac-secs
      :else
      (let [[_ body ?frac-secs offset] (parse-stamp timestamp)
            ?frac-secs-str             (when ?frac-secs
                                         (apply str
                                                ?frac-secs
                                                ;; pad
                                                (repeat (- 9 (count ?frac-secs))
                                                        "0")))]
        ;; zulu or zero-offset stamps can be handled mechanically
        (if (or zulu? (= "+00:00" offset))
          (if ?frac-secs-str
            (format "%s.%sZ"
                    body
                    ?frac-secs-str)
            ;; let's add 'em
            (format "%s.000000000Z"
                    body))
          ;; if none of that is true, we have an offset, and need to parse with
          ;; the platform lib. In clojure instants are precise so we can just do
          ;; it. In cljs, we need to override it
          (normalize-inst (parse-inst timestamp)))))))

(s/fdef now-stamp
  :args (s/cat)
  :ret ::normalized-stamp)

(defn now-stamp
  "Return the current time as a normalized stamp"
  []
  (normalize-inst (Instant/now)))

(s/fdef in-order?
  :args (s/cat :stamps (s/every ::normalized-stamp))
  :ret boolean?)

(defn in-order?
  "Returns true if the stamps are provided in order"
  [stamps]
  (= stamps
     (sort stamps)))

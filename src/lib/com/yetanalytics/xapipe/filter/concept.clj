(ns com.yetanalytics.xapipe.filter.concept
  "Functions to apply concept-based filtering to statement streams.")


(defn verb-validator
  "takes an xapi verb id and returns a fn that returns true if a statement
  contains the verb and false if not"
  [verb-id]
  (fn [stmt]
    ;; todo: substmt
    (= verb-id (get-in stmt [:verb :id]))))

(defn activity-type-validator
  "takes an xapi activity type id and returns a fn that returns true if a
  statement contains an activity of the type, and false if not"
  [activity-type-id]
  (fn [stmt]
    ;; todo: all activities checked
    (= activity-type-id
       (get-in stmt [:object :definition :type]))))

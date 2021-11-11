(ns com.yetanalytics.xapipe.test-support.lrs
  "LRS and LRS Facade facilities"
  (:require [com.yetanalytics.lrs.protocol :as lrsp]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp]))

;; An LRS that accepts statements but does not retain them
(deftype SinkLRS []
  lrsp/StatementsResource
  (-store-statements [_ _ statements attachments]
    {:statement-ids
     (into []
           (map #(ss/normalize-id (get % "id"))
                (map
                 (fn [s stamp]
                   (ss/prepare-statement
                    (assoc s "stored" stamp)))
                 statements
                 (timestamp/stamp-seq))))})
  (-consistent-through [_ _ _]
    (ss/now-stamp))
  lrsp/LRSAuth
  (-authenticate [_ _]
    {:result
     {:scopes #{:scope/all}
      :prefix ""
      :auth   {:no-op {}}}})
  (-authorize [_ _ _]
    {:result true}))

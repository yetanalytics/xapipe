(ns com.yetanalytics.xapipe.test-support.lrs
  "LRS and LRS Facade facilities"
  (:require [com.yetanalytics.lrs.protocol :as lrsp]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.statements.timestamp :as timestamp]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [com.yetanalytics.lrs.util :as lrsu]))

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

(defn- make-more-url
  [xapi-path-prefix params last-id agent]
  (str xapi-path-prefix
       "/statements?"
       (lrsu/form-encode
        (cond-> params
          true (assoc :from last-id)
          ;; Re-encode the agent if present
          agent (assoc :agent (lrsu/json-string agent))))))

(defn stream-lrs
  "Create a read-only LRS that ignores all params except limit and streams
  statements from a json file"
  [path & {:keys [xapi-prefix]
           :or {xapi-prefix "/xapi"}}]
  (let [ss-atom (atom (json/parsed-seq (io/reader path)))]
    (reify
      lrsp/StatementsResource
      (-get-statements [_
                        _
                        {:keys [limit]
                         :or {limit 50}}
                        _]
        (let [[batch rest-ss] (split-at limit @ss-atom)]
          (reset! ss-atom rest-ss)
          {:attachments []
           :statement-result
           (cond-> {:statements []}
             (not-empty batch)
             (update :statements into batch)

             (not-empty rest-ss)
             (assoc :more
                    (str xapi-prefix
                         "/statements?"
                         (lrsu/form-encode
                          {:limit limit
                           :attachments true}))))}))

      (-consistent-through [_ _ _]
        (ss/now-stamp))
      lrsp/LRSAuth
      (-authenticate [_ _]
        {:result
         {:scopes #{:scope/all}
          :prefix ""
          :auth   {:no-op {}}}})
      (-authorize [_ _ _]
        {:result true}))))

(defn get-stream-lrs-range
  "Get the stored range of a large JSON file"
  [path]
  (let [ss (json/parsed-seq (io/reader path))]
    [(-> ss first (get "stored"))
     (-> ss last (get "stored"))]))

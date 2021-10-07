(ns com.yetanalytics.xapipe.xapi
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.client.multipart-mixed :as multipart]
            [xapi-schema.spec :as xs]))

(s/fdef attachment-hashes
  :args (s/cat :statement ::xs/statement)
  :ret (s/every :attachment/sha2))

(defn attachment-hashes
  "Get any attachment hashes."
  [{:strs [attachments
           object]}]
  (distinct
   (keep
    (fn [{:strs [sha2]}]
      sha2)
    (concat attachments
            (get object "attachments")))))

(s/def ::source-statement
  (s/keys :req-un [::xs/statement
                   ::multipart/attachments]))

(s/fdef response->statements
  :args (s/cat :response ::client/get-response)
  :ret (s/every ::source-statement))

(defn response->statements
  "Break a response down into statements paired with one or more attachments"
  [{{{:strs [statements]} :statement-result
     :keys [attachments]} :body}]
  (:acc
   (reduce
    (fn [{:keys [atts] :as state} s]
      (let [hashes (attachment-hashes s)]
        ;; attachments can dup in a batch, make sure we only give out each one
        ;; to 1 statement
        (if-let [found (not-empty (select-keys atts hashes))]
          (-> state
              (update :acc
                      conj
                      {:statement s
                       :attachments (into [] (-> found
                                                 vals
                                                 (->> (map first))))})
              (update :atts
                      (fn [atts']
                        (reduce-kv
                         (fn [m k v]
                           (if (get found k)
                             (if-let [rv (not-empty (rest v))]
                               (assoc m k (into [] rv))
                               m)
                             (assoc m k v)))
                         {}
                         atts'))))
          (update state
                  :acc conj
                  {:statement s
                   :attachments []}))))
    {:atts (group-by :sha2 attachments)
     :acc []}
    statements)))

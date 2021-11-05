(ns com.yetanalytics.xapipe.xapi
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.client.multipart-mixed :as multipart]
            [xapi-schema.spec :as xs]))

(s/fdef attachment-hashes
  :args (s/cat :statement
               ::xs/statement)
  :ret (s/every (s/tuple :attachment/sha2 boolean?)))

(defn attachment-hashes
  "Get any attachment hashes in a statement.
  Returns a tuple of [sha2, file-url?]"
  [{:strs [attachments
           object]}]
  (distinct
   (keep
    (fn [{:strs [sha2 fileUrl]}]
      [sha2 (some? fileUrl)])
    (concat attachments
            (get object "attachments")))))

(s/def ::source-statement
  (s/keys :req-un [::xs/statement
                   ::multipart/attachments]))

(s/def ::atts-in
  (s/map-of ::xs/sha2
            (s/every ::multipart/attachment
                     :gen-max 1)
            :gen-max 1))

(s/def ::atts-out
  (s/map-of ::xs/sha2
            (s/every ::multipart/attachment
                     :gen-max 1)
            :gen-max 1))

(s/def ::atts-acc
  ::multipart/attachments)

(def attachment-args-spec
  (s/with-gen
    (s/cat :acc-map (s/keys :req-un [::atts-in
                                     ::atts-out
                                     ::atts-acc])
           :query (s/tuple ::xs/sha2 boolean?))
    (fn []
      (sgen/fmap (fn [[sha2 att]]
                   [{:atts-in {sha2 [att]}
                     :atts-out {}
                     :atts-acc []}
                    [sha2 false]])
                 (sgen/tuple
                  (s/gen ::xs/sha2)
                  (s/gen ::multipart/attachment))))))

(s/fdef find-attachments
  :args (s/with-gen
          (s/cat :acc-map (s/keys :req-un [::atts-in
                                           ::atts-out
                                           ::atts-acc])
                 :query (s/tuple ::xs/sha2 boolean?))
          (fn []
            (sgen/fmap (fn [[sha2 att]]
                         [{:atts-in {sha2 [att]}
                           :atts-out {}
                           :atts-acc []}
                          [sha2 false]])
                       (sgen/tuple
                        (s/gen ::xs/sha2)
                        (s/gen ::multipart/attachment)))))
  :ret (s/keys :req-un [::atts-in
                        ::atts-out
                        ::atts-acc]))

(defn find-attachments
  [{a-i :atts-in
    a-o :atts-out
    aa :atts-acc
    :as acc-map} [sha2 file-url?]]
  (or
   ;; Match in unused attachments
   (when-let [att (some-> a-i (get sha2) first)]
     {:atts-in (if (< 1 (count (get a-i sha2)))
                 (update a-i sha2 #(into [] (rest %)))
                 (dissoc a-i sha2))
      :atts-out (update a-o sha2 (fnil conj []) att)
      :atts-acc (conj aa att)})
   ;; Match in used attachments
   (when-let [att (some-> a-o (get sha2) first)]
     (let [dup (multipart/duplicate-attachment att)]
       {:atts-in a-i
        :atts-out (update a-o sha2 conj att)
        :atts-acc (conj aa att)}))
   ;; No match. Only OK if a file url is present
   (when file-url?
     {:atts-in a-i
      :atts-out a-o
      :atts-acc aa})
   (throw (ex-info "Invalid Multipart Response - No attachment found."
                   {:type ::attachment-not-found
                    :sha2 sha2
                    :acc-map acc-map}))))

(s/fdef response->statements
  :args
  (s/cat :response
         (s/with-gen
           (s/keys :req-un [::multipart/body])
           (fn []
             (sgen/fmap
              (fn [[s {:keys [sha2 contentType] :as att}]]
                {:body {:statement-result
                        {:statements
                         [(-> s
                              (assoc
                               "attachments"
                               [{"usageType" "http://example.com/foo"
                                 "display" {"en-US" "Generated"}
                                 "contentType" contentType
                                 "sha2" sha2
                                 "length" 0}])
                              (update "object" dissoc "attachments"))]}
                        :attachments [att]}})
              (sgen/tuple
               (s/gen ::xs/lrs-statement)
               (s/gen ::multipart/attachment))))))
  :ret (s/every ::source-statement))

(defn response->statements
  "Break a response down into statements paired with one or more attachments"
  [{{{:keys [statements]} :statement-result
     :keys [attachments]} :body}]
  (let [grouped (group-by :sha2 attachments)]
    (:acc
     (reduce
      ;; As we encounter statements that reference attachments they move from
      ;; atts-in to atts-out. If an attachment is not found in atts-in, it may
      ;; be copied from the first entry in atts-out
      (fn [state s]
        (if-let [hash-tuples (not-empty (attachment-hashes s))]
          (let [{:keys [atts-in
                        atts-out
                        atts-acc]}
                (reduce
                 find-attachments
                 (merge (select-keys state [:atts-in :atts-out])
                        {:atts-acc []})
                 hash-tuples)]
            {:atts-in atts-in
             :atts-out atts-out
             :acc (conj (:acc state) {:statement s
                                      :attachments atts-acc})})
          ;; No sha2s to check
          (update state
                  :acc conj
                  {:statement s
                   :attachments []})))
      {:atts-in grouped
       :atts-out {}
       :acc []}
      statements))))

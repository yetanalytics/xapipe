(ns com.yetanalytics.xapipe.client.json-only
  "JSON-only statement response handling. Necessary over default JSON parsing
   to shim attachments which will not exist"
  (:require [cheshire.core      :as json]
            [clojure.java.io    :as io]
            [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client.multipart-mixed :as mm])
  (:import
   [java.io
    InputStream
    ByteArrayOutputStream]))

(s/def ::body
  (s/keys :req-un
          [:xapi.statements.GET.response/statement-result
           ::mm/attachments]))

(s/fdef parse-json-body
  :args (s/cat :input-stream #(instance? InputStream %))
  :ret ::body)

(defn parse-json-body
  "Return a statement result and any attachments found"
  [^InputStream input-stream]
  (let [_statement-headers (.readHeaders input-stream)
        result-baos        (new ByteArrayOutputStream)]
      ;; write the body to the output stream
    (.readBodyData input-stream result-baos)
      ;; Return the statement result always
    (let [ss-result
          (with-open [r (io/reader (.toByteArray result-baos))]
            (json/parse-stream r))]
      {:attachments []
       :statement-result (reduce-kv
                          (fn [m k v]
                            (assoc m (keyword k) v))
                          {}
                          ss-result)})))

(s/fdef parse-response
  :args (s/cat :response map?)
  :ret (s/keys :req-un [::body]))

(defn parse-response
  "Parse + close a json body"
  [{:keys                             [body]
    :as                               resp}]
  (assoc resp :body (parse-json-body body)))

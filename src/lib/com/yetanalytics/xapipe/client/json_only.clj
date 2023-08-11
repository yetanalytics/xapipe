(ns com.yetanalytics.xapipe.client.json-only
  "JSON-only statement response handling. Necessary over default JSON parsing
   to shim attachments which will not exist"
  (:require [clj-http.client    :as http-client]
            [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client.multipart-mixed :as mm]))

(s/def ::body
  (s/keys :req-un
          [:xapi.statements.GET.response/statement-result
           ::mm/attachments]))

(s/fdef parse-response
  :args (s/cat :response map?)
  :ret (s/keys :req-un [::body]))

(defn parse-response
  "Parse + close a json body"
  [req resp]
  (let [{:keys [body]} (http-client/coerce-json-body req resp false)]
    (assoc resp :body  {:attachments []
                        :statement-result
                        (reduce-kv
                         (fn [m k v]
                           (assoc m (keyword k) v))
                         {}
                         body)})))

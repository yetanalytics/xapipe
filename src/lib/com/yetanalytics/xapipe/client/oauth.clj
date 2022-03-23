(ns com.yetanalytics.xapipe.client.oauth
  "OAuth Client Credentials Grant Support"
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as cs]))

;; Derive token URLs from OAuth2 endpoint/OIDC Issuer
(s/fdef token-url
  :args (s/cat :auth-uri ::auth-uri)
  :ret string?)

(defn token-url*
  [auth-uri]
  (str auth-uri
       (when-not (cs/ends-with? auth-uri "/")
         "/")
       "token"))

(def token-url (memoize token-url*))

;; For all token scenarios
(s/def ::auth-uri string?)
(s/def ::scope string?)

;; Client Credentials Grant
(s/def ::client-id string?)
(s/def ::client-secret string?)

(s/def ::ccg-params
  (s/keys :req-un [::auth-uri
                   ::client-id
                   ::client-secret]))

(s/fdef ccg-token-request
  :args (s/cat :params ::ccg-params)
  :ret map?)

(defn ccg-token-request
  [{:keys [auth-uri
           client-id
           client-secret]}]
  {:url (token-url auth-uri)
   :method :post
   :basic-auth [client-id client-secret]
   :form-params {:grant_type "client_credentials"}
   :as :json})

(comment

  (require '[clj-http.client :as client])

  (def token
    (-> (client/request
         (ccg-token-request
          {:auth-uri "http://0.0.0.0:8081/auth/realms/test/protocol/openid-connect"
           :client-id "lrs_client"
           :client-secret "Ia1RXJPQD7QSYQ55fSjzhPKru1axmUyG"
           }))
        :body
        :access_token))

  (let [token (-> (client/request
                   (ccg-token-request
                    {:auth-uri "http://0.0.0.0:8081/auth/realms/test/protocol/openid-connect"
                     :client-id "lrs_client"
                     :client-secret "vGxvFpk9CLtfQwGCSJlb9SvUoDByuZjN"
                     }))
                  :body
                  :access_token)]
    (-> #_(client/request
         {:url "http://0.0.0.0:8080/xapi/statements"
          :headers      {"x-experience-api-version" "1.0.3"
                         "Authorization" (format "Bearer %s" token)}
          :method       :post
          :content-type :json
          :form-params  {"id"     "00000000-0000-4000-8000-000000000000"
                         "actor"  {"mbox"       "mailto:sample.foo@example.com"
                                   "objectType" "Agent"}
                         "verb"   {"id"      "http://adlnet.gov/expapi/verbs/answered"
                                   "display" {"en-US" "answered"}}
                         "object" {"id" "http://www.example.com/tincan/activities/multipart"}}})
        (client/request
         {:url "http://0.0.0.0:8080/xapi/statements"
          :headers      {"x-experience-api-version" "1.0.3"
                         "Authorization" (format "Bearer %s" token)}
          :method       :get
          :as :json
          #_#_:query-params {:statementId "00000000-0000-4000-8000-000000000000"}})
        (clojure.pprint/pprint)))



  )

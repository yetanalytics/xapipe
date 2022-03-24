(ns com.yetanalytics.xapipe.client.oauth
  "OAuth Client Credentials Grant Support"
  (:require
   [clj-http.client :as client]
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
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

(defonce token-cache
  (atom {}))

(defn- token-cache-key
  [{:keys [auth-uri
           client-id]}]
  (format "%s|%s" auth-uri client-id))

(defn get-ccg-token!
  "Given client credentials grant params and kwarg options, attempt to get a
  token, either from the cache or remote. Expires cache entries based on
  :expires_in on the response.

  Returns a promise channel containing a tuple:
  [:result <access_token>]
  or
  [:exception <ex info>]

  Options:
    * bump-exp-ms time to bump up expiry of a token from the cache, with the
      assumption that some time has already passed since issuance.
  "
  [{:keys [auth-uri
           client-id] :as params}
   & {:keys [bump-exp-ms]
      :or {bump-exp-ms 500}}]
  (let [ret (a/promise-chan)
        cache-key (token-cache-key params)]
    (if-let [extant-token (get @token-cache cache-key)]
      ;; If a token is already cached, return it
      (a/put! ret [:result extant-token])
      ;; If not, go get it
      (client/request
       (merge (ccg-token-request params)
              {:async true})
       (fn [{:keys [status
                    body]
             :as resp}]
         (if (= 200 status)
           (let [{:keys [access_token
                         expires_in]} body]
             ;; update the cache
             (swap! token-cache assoc cache-key access_token)
             ;; return to the user
             (a/put! ret [:result access_token])
             (when expires_in
               ;; later, remove from cache when expired
               (a/go
                 (a/<! (a/timeout
                        (max
                         (- (* expires_in 1000) bump-exp-ms)
                         ;; don't go negative if exp is super 1s or below
                         ;; for some weird reason
                         0)))
                 (swap! token-cache dissoc cache-key))))
           ;; We only want 200s from the auth-uri
           (a/put! ret
                   [:exception
                    (ex-info "OAuth Token Non-200 status"
                             {:type ::token-non-200-status
                              :status status
                              :auth-uri auth-uri
                              :client-id client-id})])))
       ;; Exceptions are wrapped and returned
       (fn [exception]
         (a/put! ret
                 [:exception
                  (ex-info "OAuth Token Exception"
                           {:type ::token-exception
                            :auth-uri auth-uri
                            :client-id client-id}
                           exception)]))))
    ;; return promise chan
    ret))

(comment
  @token-cache
  (time
   (a/<!! (get-ccg-token! {:auth-uri "http://0.0.0.0:8081/auth/realms/test/protocol/openid-connect"
                           :client-id "lrs_client"
                           :client-secret "vGxvFpk9CLtfQwGCSJlb9SvUoDByuZjN"
                           })))

  (reset! token-cache {})

  (require '[clj-http.client :as client])

  (def token
    (-> (client/request
         (ccg-token-request
          {:auth-uri "http://0.0.0.0:8081/auth/realms/test/protocol/openid-connect"
           :client-id "lrs_client"
           :client-secret "vGxvFpk9CLtfQwGCSJlb9SvUoDByuZjN"
           }))
        clojure.pprint/pprint))

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

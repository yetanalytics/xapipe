(ns com.yetanalytics.xapipe.client
  "LRS Client"
  (:require [clj-http.client :as client]
            [clj-http.core :as http]
            [clj-http.conn-mgr :as conn-mgr]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.tools.logging :as log]
            [com.yetanalytics.xapipe.client.multipart-mixed :as multipart]
            [com.yetanalytics.xapipe.client.json-only :as json-only]
            [com.yetanalytics.xapipe.client.oauth :as oauth]
            [com.yetanalytics.xapipe.metrics :as metrics]
            [com.yetanalytics.xapipe.util :as u]
            [xapi-schema.spec :as xs]
            [com.yetanalytics.xapipe.util.time :as t]
            [com.yetanalytics.xapipe.spec.common :as cspec])
  (:import [org.apache.http.impl.client CloseableHttpClient]
           [java.net ConnectException]))

;; Add multipart-mixed output coercion
(defmethod client/coerce-response-body :multipart/mixed
  [_ {:keys [status] :as resp}]
  (if (= 200 status)
    (multipart/parse-response resp)
    (do
      (log/warnf "Received multipart response with non-200 status %d"
                 status)
      (update resp :body slurp))))

;; Add json-only output coercion
(defmethod client/coerce-response-body :json-only
  [req {:keys [status] :as resp}]
  (if (= 200 status)
    (json-only/parse-response req resp)
    (do
      (log/warnf "Received json response with non-200 status %d"
                 status)
      (update resp :body slurp))))

;; Config needed for all requests
(s/def ::url-base
  string?)

(s/def ::xapi-prefix ;; with leading slash like /xapi
  (s/nilable string?))

;; basic auth support
(s/def ::username string?)
(s/def ::password string?)

;; json-only mode support
(s/def ::json-only boolean?)

;; token support
(s/def ::token string?)

(s/def ::request-config
  (s/keys
   :req-un [::url-base]
   :opt-un [::xapi-prefix
            ::username
            ::password
            ::json-only
            ::token
            ::oauth/oauth-params]))

;; Allow the user to pass in a subset of xAPI params to limit/filter data
;;
;; notably absent are attachments and ascending, which are default true
;;
;; since will be used initially but then overwritten when iterating
;; it is always assumed to be present and will be set to the epoch if not

(s/def ::since ::t/normalized-stamp)
(s/def ::until ::t/normalized-stamp)

(defn valid-since-until [{?since :since
                          ?until :until}]
  (if (and ?since ?until)
    (t/in-order? [?since ?until])
    true))

(def get-params-spec-base
  (s/keys
   :opt-un [::since
            ::until
            :xapi.statements.GET.request.params/limit
            :xapi.statements.GET.request.params/agent
            :xapi.statements.GET.request.params/verb
            :xapi.statements.GET.request.params/activity
            :xapi.statements.GET.request.params/registration
            :xapi.statements.GET.request.params/related_activities
            :xapi.statements.GET.request.params/related_agents
            :xapi.statements.GET.request.params/format]))

(s/def ::get-params
  (s/with-gen
    (s/and
     get-params-spec-base
     valid-since-until)
    (fn []
      (sgen/fmap
       (fn [[params stamps]]
         (let [[since until] (sort stamps)]
           (cond-> params
             (:since params)
             (assoc :since since)
             (:until params)
             (assoc :until until))))
       (sgen/tuple
        (s/gen
         get-params-spec-base)
        (sgen/vector-distinct
         (s/gen ::t/normalized-stamp)
         {:num-elements 2}))))))

(def epoch-stamp "1970-01-01T00:00:00.000000000Z")

(defn- escape-get-params
  [params]
  (select-keys params
               [:since
                :until
                :limit
                :agent
                :verb
                :activity
                :registration
                :related_activities
                :related_agents
                :format]))

(defn get-request-base [json-only?]
  {:headers      {"x-experience-api-version" "1.0.3"}
   :method       :get
   :as           (if json-only? :json-only :multipart/mixed)
   :query-params {:ascending   true
                  :attachments (not json-only?)}})

(s/def ::more string?) ;; more link

(s/fdef get-request
  :args (s/cat :config ::request-config
               :params ::get-params
               :more   (s/? ::more))
  :ret map?)

(defn get-request
  "Form a /statements GET request"
  [{:keys [url-base
           xapi-prefix
           username
           password
           token
           oauth-params
           json-only]
    :or   {xapi-prefix "/xapi"}}
   get-params
   & [?more]]
  (cond-> (if (not-empty ?more)
            ;; Using More Link
            (-> (get-request-base json-only)
                (assoc :url
                       (format "%s%s"
                               url-base
                               ?more))
                (dissoc :query-params))
            (-> (get-request-base json-only)
                (assoc :url
                       (format "%s%s/statements"
                               url-base
                               xapi-prefix))
                (update :query-params merge get-params)))
    ;; support token if provided
    (not-empty token)
    (assoc :oauth-token token)
    ;; support basic auth if provided
    (and (not-empty username)
         (not-empty password))
    (assoc :basic-auth [username password])
    ;; If OAuth support is enabled, pass through on a namespaced
    ;; keyword to be picked up in async-request
    oauth-params
    (assoc ::oauth/oauth-params oauth-params)))

(def post-request-base
  {:headers {"x-experience-api-version" "1.0.3"}
   :method  :post})

(s/fdef post-request
  :args (s/cat :config ::request-config
               :statements (s/every ::xs/statement)
               :attachments ::multipart/attachments)
  :ret map?)

(defn post-request
  [{:keys [url-base
           xapi-prefix
           username
           password
           token
           oauth-params]
    :or   {xapi-prefix "/xapi"}}
   statements
   attachments]
  (let [boundary (multipart/gen-boundary)]
    (-> post-request-base
        (merge
         {:url  (format "%s%s/statements"
                        url-base
                        xapi-prefix)
          :body (multipart/post-body boundary statements attachments)})

        (assoc-in [:headers "content-type"]
                  (format "multipart/mixed; boundary=%s" boundary))
        (cond->
          ;; support token if provided
         (not-empty token)
          (assoc :oauth-token token)
          ;; support basic auth if provided
          (and (not-empty username)
               (not-empty password))
          (assoc :basic-auth [username password])
          ;; If OAuth support is enabled, pass through on a namespaced
          ;; keyword to be picked up in async-request
          oauth-params
          (assoc ::oauth/oauth-params oauth-params)))))

(def rate-limit-status?
  #{420 429})

(def retryable-error?
  #{502 503 504})

(def retryable-oauth-error?
  #{401})

(defn retryable-status?
  "Is the HTTP status code one we care to retry?"
  [status
   & {:keys [oauth?]
      :or {oauth? false}}]
  (and status
       (or
        (rate-limit-status? status)
        (retryable-error? status)
        (when oauth?
          (retryable-oauth-error? status)))))

(defn retryable-exception?
  "Is this a client exception we can retry?"
  [ex]
  (instance? ConnectException ex))

(declare async-request)

(defn maybe-retry
  [ret-chan reporter req attempt backoff-opts & [?cause]]
  (if-let [backoff (u/backoff-ms
                    attempt
                    backoff-opts)]
    ;; Go async, wait and relaunch
    (a/go
      (a/<! (a/timeout backoff))
      (async-request
       req
       :ret-chan ret-chan
       :attempt (inc attempt)
       :backoff-opts backoff-opts
       :reporter reporter))
    (a/put!
     ret-chan
     [:exception
      (if ?cause
        (ex-info
         (format "Max retries reached: %s"
                 (ex-message ?cause))
         {:type         ::max-retry
          :backoff-opts backoff-opts}
         ?cause)
        (ex-info
         "Max retries reached!"
         {:type         ::max-retry
          :backoff-opts backoff-opts}))])))

(defn async-request
  "Perform an async http request, returning a promise channel with tuple
  of either:

  [:response <resp>]
  or
  [:exception <ex>]

  Only status 200 responses will be returned, all others will be wrapped in an
  ex-info."
  [request
   & {:keys [ret-chan
             attempt
             backoff-opts
             reporter]
      :or {attempt 0
           backoff-opts {:budget 10000
                         :max-attempt 10}}}]
  (let [ret (or ret-chan (a/promise-chan))]
    (a/go
      (let [[oauth-tag oauth-v] (if-let [oauth-params (::oauth/oauth-params request)]
                                  (a/<! (oauth/get-token! oauth-params))
                                  [:result nil])]
        (case oauth-tag
          :exception
          (a/put! ret
                  [:exception
                   oauth-v])
          :result
          (let [req (cond-> (assoc request
                                   ;; don't throw so we handle resp as data
                                   :throw-exceptions false
                                   :async true
                                   ;; docs mention this but it is probably not needed
                                   :async? true)
                      ;; If there is an oauth token result, use it, overwriting
                      ;; any existing token
                      oauth-v (assoc :oauth-token oauth-v))]
            (client/request
             req
             (fn [{:keys [status]
                   :as   resp}]
               (cond
                 ;; Both our GET and POST expect 200
                 ;; If status is 200, pass the response
                 (= status 200)
                 (a/put! ret [:response resp])

                 ;; Retry based on retryable status
                 (retryable-status? status :oauth? (some? oauth-v))
                 (maybe-retry
                  ret
                  reporter
                  req
                  attempt
                  backoff-opts)

                 :else
                 (a/put!
                  ret
                  [:exception
                   (ex-info "Non-200 Request Status"
                            {:type     ::request-fail
                             :response resp})])))
             (fn [exception]
               (if (retryable-exception? exception)
                 (maybe-retry
                  ret
                  reporter
                  req
                  attempt
                  backoff-opts
                  exception)
                 (a/put! ret
                         [:exception
                          exception]))))))))
    ret))

(s/def ::poll-interval
  nat-int?)

(s/def ::conn-mgr any?) ;; Reusable async conn mgr
(s/def ::http-client any?) ;; http client to thread

(s/def ::conn-opts (s/keys :req-un [::conn-mgr ::http-client]))

(s/def ::backoff-opts u/backoff-opts-spec)

(s/fdef get-loop
  :args (s/cat :out-chan ::cspec/channel
               :stop-chan ::cspec/channel
               :config ::request-config
               :init-params ::get-params
               :poll-interval ::poll-interval
               :kwargs
               (s/keys*
                :opt-un
                [::backoff-opts
                 ::conn-opts
                 ::metrics/reporter]))
  :ret ::cspec/channel)

(defn get-loop
  "Puts responses from an LRS onto out-chan forever or until it
  returns an error and closes or recieves a signal on stop-chan.
  Returns a channel that will close when stopped."
  [out-chan
   stop-chan
   config
   {init-since :since
    ?until     :until
    :as        init-params
    :or        {init-since epoch-stamp}}
   poll-interval
   & {:keys [backoff-opts
             conn-opts
             reporter]
      :or {reporter (metrics/->NoopReporter)}}]
  (let [backoff-opts (or backoff-opts
                         {:budget 10000
                          :max-attempt 10})
        init-req (get-request
                  config
                  init-params)]
    (a/go
      (loop [req   init-req
             since init-since]
        (log/debug "GET" (:url req) :since since)
        (let [req-chan (async-request
                        (merge req
                               conn-opts)
                        :backoff-opts backoff-opts
                        :reporter reporter)
              [v p]    (a/alts! [req-chan stop-chan])]
          (if (identical? p stop-chan)
            (do
              (log/info "Stop called" :data v)
              ;; finish pending req and bail
              (a/<! req-chan)
              (a/close! out-chan))
            (let [[tag resp :as ret] v]
              (case tag
                :response
                (let [{{consistent-through-h "X-Experience-API-Consistent-Through"}
                       :headers
                       {{:keys [statements more]} :statement-result}
                       :body
                       :keys [request-time]} resp
                      ?last-stored (some-> statements
                                           peek
                                           (get "stored")
                                           t/normalize-stamp)
                      consistent-through (t/normalize-stamp consistent-through-h)
                      next-since (or ?last-stored since)]
                  ;; If there are statements, emit them before continuing.
                  ;; This operation will park for takers.
                  (when (not-empty statements)
                    (log/debugf "emitting %d statements, last stored is %s"
                                (count statements)
                                ?last-stored)
                    (a/>! out-chan
                          ret))
                  ;; Handle metrics
                  (metrics/histogram reporter
                                     :xapipe/source-request-time
                                     (metrics/millis->frac-secs request-time))

                  (cond
                    ;; If the more link indicates there are more statements to
                    ;; provide, immediately attempt to get them.
                    (not-empty more) (do
                                       (log/debugf
                                        "more link %s found, redispatching"
                                        more)
                                       (recur
                                        (get-request
                                         config
                                         {} ;; has no effect with more
                                         more)
                                        next-since))

                    ;; The lack of a more link means we are at the end of what the
                    ;; LRS has for the given query.
                    ;; At this point we check for an until and maybe terminate
                    (and ?until
                         (not
                          (pos?
                           (compare ?until
                                    consistent-through))))
                    (do
                      (log/debugf
                       "terminating because %s consistent-through is equal or later than %s until"
                       consistent-through ?until)
                      (a/close! out-chan))
                    ;; With no more link or until, we're polling.
                    ;; Wait for the specified time
                    :else (do
                            (log/debugf "waiting %d ms..." poll-interval)
                            (a/<! (a/timeout poll-interval))
                            (recur
                             (get-request
                              config
                              ;; Ensure since is updated if it should be
                              (assoc init-params
                                     :since
                                     next-since))
                             next-since))))
                :exception
                (do (a/>! out-chan ret)
                    (a/close! out-chan)))))))
      ;; if a client was passed in, close it!
      (when-let [http-client (:http-client conn-opts)]
        (.close ^CloseableHttpClient http-client)))))

(s/def ::get-response
  (s/keys :req-un [::multipart/body]))

(s/def ::get-success
  (s/tuple :response
           ::get-response))

(s/def ::get-exception
  (s/tuple :exception
           #(instance? Exception %)))

(s/def ::get-ret
  (s/or :response
        ::get-success
        :exception
        ::get-exception))

(s/def ::conn-mgr-opts map?) ;; map of opts to build a conn mgr with
(s/def ::http-client-opts map?) ;; map of opts for http client

(s/fdef init-conn-mgr
  :args (s/cat :conn-mgr-opts ::conn-mgr-opts)
  :ret ::conn-mgr)

(defn init-conn-mgr
  "Return and initialize an async conn-mgr and client"
  [conn-mgr-opts]
  (conn-mgr/make-reuseable-async-conn-manager
   conn-mgr-opts))

(s/fdef init-client
  :args (s/cat
         :conn-mgr ::conn-mgr
         :client-opts ::http-client-opts)
  :ret ::http-client)

(defn init-client
  [conn-mgr
   client-opts]
  (http/build-async-http-client
   client-opts
   conn-mgr))

(s/fdef shutdown
  :args (s/cat :conn-mgr ::conn-mgr))

(defn shutdown
  "Shutdown a connection manager"
  [conn-mgr]
  (conn-mgr/shutdown-manager conn-mgr))

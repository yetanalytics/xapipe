(ns com.yetanalytics.xapipe.client
  "LRS Client"
  (:require #_[org.httpkit.client :as client]
            [clj-http.client :as client]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client.multipart-mixed :as multipart]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [com.yetanalytics.xapipe.util.time :as t]))

;; TODO: remove last-stored emission if it doesn't get used
;; TODO: Pick and enable actual loggings
(defn- debug
  "DEV logging"
  [& args]
  (apply println "DEBUG: " args))

;; Add multipart-mixed output coercion
(defmethod client/coerce-response-body :multipart/mixed
  [_ resp]
  (multipart/parse-response resp))

;; Config needed for all requests
(s/def ::url-base
  string?)

(s/def ::xapi-prefix ;; with leading slash like /xapi
  (s/nilable string?))

;; basic auth support
(s/def ::username string?)
(s/def ::password string?)

(s/def ::request-config
  (s/keys
   :req-un [::url-base]
   :opt-un [::xapi-prefix
            ::username
            ::password]))

;; Allow the user to pass in a subset of xAPI params to limit/filter data
;;
;; notably absent are attachments and ascending, which are default true
;;
;; since will be used initially but then overwritten when iterating
;; it is always assumed to be present and will be set to the epoch if not
;;
;; until will cause the transfer to terminate TODO: this

(s/def ::get-params
  (s/keys
   :opt-un [:xapi.statements.GET.request.params/since
            :xapi.statements.GET.request.params/until
            :xapi.statements.GET.request.params/limit
            :xapi.statements.GET.request.params/agent
            :xapi.statements.GET.request.params/verb
            :xapi.statements.GET.request.params/activity
            :xapi.statements.GET.request.params/registration
            :xapi.statements.GET.request.params/related_activities
            :xapi.statements.GET.request.params/related_agents
            :xapi.statements.GET.request.params/format]))

(def epoch-stamp "1970-01-01T00:00:00Z")

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

(def get-request-base
  {:headers      {"x-experience-api-version" "1.0.3"}
   :method       :get
   :as           :multipart/mixed
   :query-params {:ascending   true
                  :attachments true}})

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
           password]
    :or   {xapi-prefix "/xapi"}}
   get-params
   & [?more]]
  (cond-> (if (not-empty ?more)
            ;; Using More Link
            (-> get-request-base
                (assoc :url
                       (format "%s%s"
                               url-base
                               ?more))
                (dissoc :query-params))
            (-> get-request-base
                (assoc :url
                       (format "%s%s/statements"
                               url-base
                               xapi-prefix))
                (update :query-params merge get-params)))

    ;; support basic auth if provided
    (and (not-empty username)
         (not-empty password))
    (assoc :basic-auth [username password])))

(def post-request-base
  {:headers {"x-experience-api-version" "1.0.3"}
   :method  :post
   :as      :json})

(s/fdef post-request
  :args (s/cat :config ::request-config
               :statements (s/every ::xs/statement)
               :attachments (s/every ::multipart/attachment))
  :ret map?)

(defn post-request
  [{:keys [url-base
           xapi-prefix
           username
           password]
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
            ;; support basic auth if provided
            (and (not-empty username)
                 (not-empty password))
          (assoc :basic-auth [username password])))))

(defn async-request
  "Perform an async http request, returning a promise channel with tuple
  of either:

  [:response <resp>]
  or
  [:exception <ex>]

  Only status 200 responses will be returned, all others will be wrapped in an
  ex-info."
  [request]
  (let [ret (a/promise-chan)
        fn1 (fn [open?]
              (when open? (a/close! ret)))]
    (client/request
     (assoc request :async? true)
     (fn [{:keys [status]
           :as   resp}]
       (a/put! ret
               (if (= status 200)
                 [:response resp]
                 [:exception
                  (ex-info "Non-200 Request Status"
                           {:type     ::request-fail
                            :response resp})])
               fn1))
     (fn [exception]
       (a/put! ret
               [:exception
                (ex-info (format "Unhandled LRS request exception: %"
                                 (ex-message exception))
                         {:type ::unhandled-request-exception}
                         exception)]
               fn1)))
    ret))

(s/def ::poll-interval
  nat-int?)

(s/fdef get-chan
  :args (s/cat :out-chan any?
               :stop-chan any?
               :config ::request-config
               :init-params ::get-params
               :poll-interval ::poll-interval)
  :ret any?)

(defn get-chan
  "Returns a channel that will return responses from an LRS forever or until it
  returns an error and closes or recieves a signal on stop-chan.."
  [out-chan
   stop-chan
   config
   {init-since :since
    ?until     :until
    :as        init-params
    :or        {init-since epoch-stamp}}
   poll-interval]
  (let [init-req (get-request
                  config
                  init-params)]
    (a/go-loop [req   init-req
                since init-since]
      (debug 'req req 'since since)
      (let [req-chan (async-request
                      req)
            [v p]    (a/alts! [req-chan stop-chan])]
        (if (identical? p stop-chan)
          (do
            ;; finish pending req and bail
            (a/<! req-chan)
            (a/close! out-chan))
          (let [[tag resp :as ret] v]
            (case tag
              :response
              (let [{{consistent-through "X-Experience-API-Consistent-Through"}
                     :headers
                     {{:strs [statements more]} :statement-result}
                     :body}      resp
                    ?last-stored (some-> statements
                                         peek
                                         (get "stored"))]
                ;; If there are statements, emit them before continuing.
                ;; This operation will park for takers.
                (when (not-empty statements)
                  (debug (format "emitting %d statements, last stored is %s"
                                 (count statements)
                                 ?last-stored))
                  (a/>! out-chan
                        (assoc-in ret [1 ::last-stored] ?last-stored)))
                (cond
                  ;; If the more link indicates there are more statements to
                  ;; provide, immediately attempt to get them.
                  (not-empty more) (do
                                     (debug
                                      (format "more link %s found, redispatching"
                                              more))
                                     (recur
                                      (get-request
                                       config
                                       {} ;; has no effect with more
                                       more)
                                      ;; Set since for the cursor if we have new stuff
                                      (or ?last-stored since)))

                  ;; The lack of a more link means we are at the end of what the
                  ;; LRS has for the given query.
                  ;; At this point we check for an until and maybe terminate
                  (and ?until
                       (not= 1
                             (t/stamp-cmp
                              ?until
                              consistent-through)))
                  (do
                    (debug
                     (format "terminating because %s consistent-through is equal or later than %s until"
                             consistent-through ?until))
                    (a/close! out-chan))
                  ;; With no more link or until, we're polling.
                  ;; Wait for the specified time
                  :else (do
                          (debug (format "waiting %d ms..." poll-interval))
                          (a/<! (a/timeout poll-interval))
                          (recur
                           (get-request
                            config
                            ;; Update Since to the LRS header
                            (assoc init-params
                                   :since
                                   consistent-through))
                           consistent-through))))
              :exception
              (do (a/>! out-chan ret)
                  (a/close! out-chan)))))))
    out-chan))

(s/def ::get-response
  (s/keys :req-un [::last-stored ::multipart/body]))

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
(comment

  (do
    (def out-chan (a/chan))
    (def stop-chan (a/promise-chan))

    (def gchan
      (get-chan
       out-chan
       stop-chan
       {:url-base    "http://localhost:8080"
        :xapi-prefix "/xapi"}
       {#_#_:until "2021-10-06T16:59:07.429354000Z"}
       1000)))

  (first (a/<!! gchan)) ;; will block when out of statements!

  (a/>!! stop-chan :foo)

  (-> (client/request
       (get-request
        {:url-base    "http://localhost:8080"
         :xapi-prefix "/xapi"}
        {})
       )
      com.yetanalytics.xapipe.xapi/response->statements
      (->> (mapcat :attachments))
      )

  (def resp (client/request
       (get-request
        {:url-base    "http://localhost:8080"
         :xapi-prefix "/xapi"}
        {})
       )

    )



  (-> (async-request
       (get-request
        {:url-base    "http://localhost:8080"
         :xapi-prefix "/xapi"}
        {})
       )
      a/<!!
      first
      )

  ;; simple test with get and post of 1 batch
  (let [req-config                         {:url-base    "http://localhost:8080"
                                            :xapi-prefix "/xapi"}
        ;; Get
        {{{:strs [statements]}
          :statement-result
          :keys [attachments]} :body
         :as                   get-result} (client/request
                                            (get-request
                                             req-config
                                             {}))

        ;; Post
        post-resp (client/request
                   (post-request
                    req-config
                    statements
                    attachments) )]
    (clojure.pprint/pprint post-resp))



  )

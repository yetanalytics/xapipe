(ns com.yetanalytics.xapipe.client
  "LRS Client"
  (:require #_[org.httpkit.client :as client]
            [clj-http.client :as client]
            [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client.multipart-mixed :as multipart]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]))

;; Add multipart-mixed output coercion
(defmethod client/coerce-response-body :multipart/mixed
  [_ resp]
  (multipart/parse-response resp))

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

(def get-request-base
  {:headers      {"x-experience-api-version" "1.0.3"}
   :method       :get
   :as           :multipart/mixed
   :query-params {:ascending   true
                  :attachments true}})

(s/def ::more string?) ;; more link
(s/def ::since ::xs/timestamp) ;; since arg
(s/def ::limit pos-int?)

(s/fdef get-request
  :args (s/cat :config ::request-config
               :kwargs (s/keys* :opt-un [::more
                                         ::since
                                         ::limit]))
  :ret map?)

(defn get-request
  "Form a /statements GET request"
  [{:keys [url-base
           xapi-prefix
           username
           password]
    :or   {xapi-prefix "/xapi"}}
   & {?since :since
      ?more  :more
      ?limit :limit}]
  (let [more? (not-empty ?more)]
    (cond-> (merge
             get-request-base
             {:url
              (if more?
                (format "%s%s"
                        url-base
                        ?more)
                (format "%s%s/statements"
                        url-base
                        xapi-prefix))})
      ;; support basic auth if provided
      (and (not-empty username)
           (not-empty password))
      (assoc :basic-auth [username password])
      ;; query params will be included in the more link
      more?
      (dissoc :query-params)

      (and (not more?)
           ?since)
      (assoc-in [:query-params :since] ?since)

      (and (not more?)
           ?limit)
      (assoc-in [:query-params :limit] ?limit))))

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
                (ex-info "Unhandled LRS request exception"
                         {:type ::unhandled-request-exception}
                         exception)]
               fn1)))
    ret))

(s/def ::poll-interval
  nat-int?)

(s/fdef get-chan
  :args (s/cat :config ::request-config
               :poll-interval ::poll-interval
               :stop-chan any?
               :kwargs
               (s/keys* :opt-un [::more
                                 ::since
                                 ::limit]))
  :ret any?)

(defn get-chan
  "Returns a channel that will return responses from an LRS forever or until it
  returns an error and closes or recieves a signal on stop-chan.."
  [config
   poll-interval
   stop-chan
   & req-kwargs]
  (let [out-chan (a/chan)]
    (a/go-loop [req (apply
                     get-request
                     config
                     req-kwargs)
                since (some-> req
                              :query-params
                              :since)]
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
              (let [{{{:strs [statements more]} :statement-result} :body} resp]
                (if (not-empty statements)
                  (let [last-stored (-> statements
                                        peek
                                        (get "stored"))]
                    ;; put on the (unbuffered) channel. Will park
                    (a/>! out-chan
                          (assoc-in ret [1 ::last-stored] last-stored))
                    (recur
                     ;; A more link gives us am automatic way to continue
                     (if (not-empty more)
                       (get-request
                        config
                        :more more)
                       (get-request
                        config
                        :since last-stored))
                     ;; update
                     last-stored))
                  ;; With no statements we're polling.
                  ;; Wait for the specified time
                  (do
                    (a/<! (a/timeout poll-interval))
                    (recur
                     (cond
                       (not-empty more) (get-request
                                         config
                                         :more more)
                       since            (get-request
                                         config
                                         :since since)
                       :else            (apply
                                         get-request
                                         config
                                         req-kwargs))
                     since))))
              :exception
              (do (a/>! out-chan ret)
                  (a/close! out-chan)))))))
    out-chan))

(comment

  (def stop-chan (a/promise-chan))

  (def gchan
    (get-chan
     {:url-base    "http://localhost:8080"
      :xapi-prefix "/xapi"}
     1000
     stop-chan))

  (first (a/<!! gchan)) ;; will block when out of statements!

  (a/>!! stop-chan :foo)

  (-> (client/request
       (get-request
        {:url-base    "http://localhost:8080"
         :xapi-prefix "/xapi"})
       )
      :body
      :attachments
      )

  (-> (async-request
       (get-request
        {:url-base    "http://localhost:8080"
         :xapi-prefix "/xapi"})
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
                                             req-config))

        ;; Post
        post-resp (client/request
                   (post-request
                    req-config
                    statements
                    attachments) )]
    (clojure.pprint/pprint post-resp))



  )

(ns com.yetanalytics.xapipe.client
  "LRS Client"
  (:require #_[org.httpkit.client :as client]
            [clj-http.client :as client]
            [clj-http.util :as util]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [xapi-schema.spec :as xs]
            [xapi-schema.spec.resources :as xsr]
            [clojure.string :as cs]
            [byte-streams :as bs]
            [clojure.core.async :as a])
  (:import
   [java.io IOException InputStream ByteArrayOutputStream File]
   [org.apache.commons.fileupload
    MultipartStream
    MultipartStream$MalformedStreamException]))

#_(set! *warn-on-reflection* true)

(s/def ::tempfile
  #(instance? File %))

(s/fdef create-tempfile!
  :args (s/cat :sha2 string?)
  :ret ::tempfile)

(defn create-tempfile!
  "Create a unique but identifiable tempfile"
  [sha2]
  (File/createTempFile
   "xapipe_attachment_"
   (format "_%s" sha2)))

(s/fdef parse-headers
  :args (s/cat :headers string?)
  :ret (s/map-of string? string?))

(defn parse-headers
  "Parse a part's headers and return a map suitable for use in an attachment"
  [headers]
  (-> headers
      cs/split-lines
      (->>
       (into {}
             (map #(cs/split % #":" 2))))))

;; an Attachment is the xapi properties + a temp file
(s/def ::attachment
  (s/keys :req-un
          [:attachment/sha2
           :attachment/contentType
           ::tempfile]))

(s/def ::attachments (s/every ::attachment))

(s/fdef parse-head
  :args (s/cat :stream #(instance? MultipartStream %))
  :ret :xapi.statements.GET.response/statement-result)

(defn parse-head
  "Parse out the head of the stream, a statement result object"
  [^MultipartStream stream]
  (let [_statement-headers (.readHeaders stream)
        result-baos (new ByteArrayOutputStream)]
    ;; write the body to the output stream
    (.readBodyData stream result-baos)
    ;; Return the statement result always
    (with-open [r (io/reader (.toByteArray result-baos))]
      (json/parse-stream r))))

(s/fdef parse-tail
  :args (s/cat :stream #(instance? MultipartStream %))
  :ret ::attachments)

(defn parse-tail
  "Parse any available attachments in the stream"
  [^MultipartStream stream]
  (loop [acc []]
    (if (.readBoundary stream)
      (let [{part-ctype "Content-Type"
             part-sha2  "X-Experience-API-Hash"}
            (-> stream
                .readHeaders
                parse-headers)]
        ;; xAPI Multipart Parts must have these
        (if (and part-ctype
                 part-sha2)
          (let [tempfile (create-tempfile! part-sha2)]
            ;; Write body to a tempfile
            (with-open [os (io/output-stream tempfile)]
              (.readBodyData stream os))
            (recur
             (conj acc
                   {:sha2 part-sha2
                    :contentType part-ctype
                    :tempfile tempfile})))
          (throw (ex-info "Invalid xAPI Part"
                          {:type ::invalid-xapi-part}))))
      acc)))

(s/def ::body
  (s/keys :req-un
          [:xapi.statements.GET.response/statement-result
           ::attachments]))

(s/fdef parse-multipart-body
  :args (s/cat :input-stream #(instance? InputStream %)
               ;; contents of the Content-Type header
               :content-type-str string?)
  :ret ::body)

(defn parse-multipart-body
  "Return a statement result and any attachments found"
  [^InputStream input-stream
   ^String content-type-str]
  (let [{:keys [content-type]
         {^String boundary-str :boundary
          :keys [^String charset]
          :or {charset "UTF-8"}} :content-type-params} (util/parse-content-type
                                                        content-type-str)
        boundary (.getBytes boundary-str charset)]
    (with-open [input input-stream]
      (try
        (let [multipart-stream (new MultipartStream input-stream boundary)]
          (if (.skipPreamble multipart-stream)
            ;; The first bit should be statements
            {:statement-result (parse-head multipart-stream)
             ;; If there are attachments, find and coerce them
             :attachments (parse-tail multipart-stream)}
            (throw (ex-info "Empty Stream"
                            {:type ::empty-stream}))))
        (catch MultipartStream$MalformedStreamException ex
          (throw (ex-info "Malformed Stream"
                          {:type ::malformed-stream}
                          ex)))
        (catch IOException ex
          (throw (ex-info "Read Error"
                          {:type ::read-error}
                          ex)))))))

(s/fdef parse-response
  :args (s/cat :response map?)
  :ret (s/keys :req-un [::body]))

(defn parse-response
  "Parse + close a multipart body"
  [{:keys [body]
    {content-type-str "Content-Type"} :headers
    :as resp}]
  (assoc resp :body (parse-multipart-body body content-type-str)))

(defmethod client/coerce-response-body :multipart/mixed
  [_ resp]
  (parse-response resp))

(s/fdef post-body
  :args (s/cat :boundary string?
               :statements (s/every ::xs/statement)
               :attachments ::attachments)
  :ret #(instance? InputStream %))

(def crlf "\r\n")

(defn post-body
  "Return an input stream with the POST multipart body"
  [boundary
   statements
   attachments]
  (bs/to-input-stream
   (concat
    (cons
     (str "--"
          boundary
          crlf
          "Content-Type:application/json"
          crlf
          crlf
          (json/generate-string statements))
     (mapcat
      (fn [{:keys [sha2 contentType tempfile]}]
        [(str crlf
              "--"
              boundary
              crlf
              (format "Content-Type:%s" contentType)
              crlf
              "Content-Transfer-Encoding:binary"
              crlf
              (format "X-Experience-API-Hash:%s" sha2)
              crlf
              crlf)
         tempfile])
      attachments))
    [(str crlf
          "--"
          boundary
          "--")])))

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
  {:headers {"x-experience-api-version" "1.0.3"}
   :method :get
   :as :multipart/mixed
   :query-params {:ascending true
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
    :or {xapi-prefix "/xapi"}}
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
   :method :post
   :as :json})

(s/fdef post-request
  :args (s/cat :config ::request-config
               :statements (s/every ::xs/statement)
               :attachments (s/every ::attachment))
  :ret map?)

;; https://stackoverflow.com/a/67545577/3532563
(defn- gen-boundary
  "Generate a multipart boundary"
  []
  (apply str (repeatedly 64 #(rand-nth "abcdefghijklmnopqrstuvwxyz0123456789"))))

(defn post-request
  [{:keys [url-base
           xapi-prefix
           username
           password]
    :or {xapi-prefix "/xapi"}}
   statements
   attachments]
  (let [boundary (gen-boundary)]
    (-> post-request-base
        (merge
         {:url (format "%s%s/statements"
                       url-base
                       xapi-prefix)
          :body (post-body boundary statements attachments)})

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
           :as resp}]
       (a/put! ret
               (if (= status 200)
                 [:response resp]
                 [:exception
                  (ex-info "Non-200 Request Status"
                           {:type ::request-fail
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

(s/fdef get-chan
  :args (s/cat :config ::request-config
               :kwargs
               (s/keys* :opt-un [::more
                                 ::since
                                 ::limit]))
  :ret any?)

(defn get-chan
  "Returns a channel that will return responses from an LRS forever or until it
  returns an error and closes. Items as specified in async-request."
  [lrs-config
   & req-kwargs]
  (let [out-chan (a/chan)]
    (a/go-loop [req (apply
                     get-request
                     lrs-config
                     req-kwargs)
                batch-idx 0]
      (let [[tag resp :as ret] (a/<!
                                (async-request
                                 req))]
        (case tag
          :response
          (do
            (a/>! out-chan ret)
            (if-let [more (some-> resp
                                  (get-in [:body
                                           :statement-result
                                           "more"])
                                  not-empty)]
              (recur (get-request
                      lrs-config
                      :more more)
                     (inc batch-idx))
              (do
                ;; TODO: Poll. Right now it closes
                (a/close! out-chan))))
          :exception
          (do (a/>! out-chan ret)
              (a/close! out-chan)))))
    out-chan))

(comment
  (count
   (a/<!! (a/into [] (get-chan
                     {:url-base "http://localhost:8080"
                      :xapi-prefix "/xapi"}))))

  (-> (client/request
       (get-request
        {:url-base "http://localhost:8080"
         :xapi-prefix "/xapi"})
       )
      :body
      :attachments
      )

  (-> (async-request
       (get-request
        {:url-base "http://localhost:8080"
         :xapi-prefix "/xapi"})
       )
      a/<!!
      first
      )

  ;; simple test with get and post of 1 batch
  (let [req-config {:url-base "http://localhost:8080"
                    :xapi-prefix "/xapi"}
        ;; Get
        {{{:strs [statements]}
          :statement-result
          :keys [attachments]} :body
         :as get-result} (client/request
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

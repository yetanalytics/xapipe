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
            [byte-streams :as bs])
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

(comment

  (def boundary ;; TODO: Dynamic
    "105423a5219f5a63362a375ba7a64a8f234da19c7d01e56800c3c64b26bb2fa0")
  (def content-type (format "multipart/mixed; boundary=%s" boundary))

  (-> (client/request
       {:url "http://localhost:8080/xapi/statements"
        :query-params {:attachments true
                       :ascending true
                       :limit 1
                       :since "2021-10-05T15:09:27.672932000Z"}
        :headers {"x-experience-api-version" "1.0.3"}
        :method :get
        :as :multipart/mixed})
      :body
      :attachments
      count)

  ;; simple test with get and post of 1 batch
  (let [;; Get
        {{{:strs [statements]}
          :statement-result
          :keys [attachments]} :body
         :as get-result} (client/request
                          {:url "http://localhost:8080/xapi/statements"
                           :query-params {:attachments true
                                          :ascending true}
                           :headers {"x-experience-api-version" "1.0.3"}
                           :method :get
                           :as :multipart/mixed})

        ;; Post
        post-resp (client/request
                   {:method :post
                    :url "http://localhost:8081/xapi/statements"
                    :headers {"x-experience-api-version" "1.0.3"
                              "content-type" (format "multipart/mixed; boundary=%s" boundary)}
                    :body (post-body boundary statements attachments)
                    :as :json})]
    (clojure.pprint/pprint post-resp))



  )

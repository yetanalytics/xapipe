(ns com.yetanalytics.xapipe.client.multipart-mixed
  "multipart/mixed handling"
  (:require [cheshire.core :as json]
            [clj-http.util :as hutil]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.string :as cs]
            [xapi-schema.spec :as xs])
  (:import
   [java.io
    Writer
    PipedOutputStream
    PipedInputStream
    IOException
    InputStream
    OutputStream
    ByteArrayOutputStream
    File]
   [org.apache.commons.fileupload
    MultipartStream
    MultipartStream$MalformedStreamException]))

(s/def ::tempfile
  (s/with-gen
    #(instance? File %)
    (fn []
      (sgen/return
       (doto (File/createTempFile
              "xapipe_gen_attachment_"
              "")
         .deleteOnExit)))))

(s/fdef create-tempfile!
  :args (s/cat :sha2 string?)
  :ret ::tempfile)

(defn create-tempfile!
  "Create a unique but identifiable tempfile"
  [sha2]
  (doto (File/createTempFile
         "xapipe_attachment_"
         (format "_%s" sha2))
    .deleteOnExit))

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

(s/def ::attachments (s/every ::attachment :gen-max 1))

(s/fdef duplicate-attachment
  :args (s/cat :attachment ::attachment)
  :ret ::attachment)

(defn duplicate-attachment
  "Given an attachment yield a copy"
  [{:keys [sha2 tempfile] :as original}]
  (let [dup-tempfile (create-tempfile! sha2)]
    (io/copy tempfile dup-tempfile)
    (assoc original :tempfile dup-tempfile)))

(s/fdef clean-tempfiles!
  :args (s/cat :attachments ::attachments))

(defn clean-tempfiles!
  "Delete all tempfiles in a series of attachments"
  [attachments]
  (doseq [{:keys [^File tempfile]} attachments]
    (.delete tempfile)))

;; WE DONT DO THAT HERE
#_(s/fdef unique-by-sha
  :args (s/cat :attachments ::attachments)
  :ret ::attachments)

#_(defn unique-by-sha
  [attachments]
  (->> attachments
      (group-by :sha2)
      vals
      (mapv first)))

(s/fdef parse-head
  :args (s/cat :stream #(instance? MultipartStream %))
  :ret :xapi.statements.GET.response/statement-result)

(defn parse-head
  "Parse out the head of the stream, a statement result object"
  [^MultipartStream stream]
  (let [_statement-headers (.readHeaders stream)
        result-baos        (new ByteArrayOutputStream)]
    ;; write the body to the output stream
    (.readBodyData stream result-baos)
    ;; Return the statement result always
    (let [ss-result
          (with-open [r (io/reader (.toByteArray result-baos))]
            (json/parse-stream r))]
      (reduce-kv
       (fn [m k v]
         (assoc m (keyword k) v))
       {}
       ss-result))))

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
                   {:sha2        part-sha2
                    :contentType part-ctype
                    :tempfile    tempfile})))
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
  (let [{:keys                                    [content-type]
         {^String boundary-str :boundary
          :keys                [^String charset]
          :or                  {charset "UTF-8"}} :content-type-params}
        (hutil/parse-content-type
         content-type-str)
        boundary (.getBytes boundary-str charset)]
    (with-open [input input-stream]
      (try
        (let [multipart-stream (new MultipartStream input-stream boundary)]
          (if (.skipPreamble multipart-stream)
            ;; The first bit should be statements
            {:statement-result (parse-head multipart-stream)
             ;; If there are attachments, find and coerce them
             :attachments      (parse-tail multipart-stream)}
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
  [{:keys                             [body]
    {content-type-str "Content-Type"} :headers
    :as                               resp}]
  (assoc resp :body (parse-multipart-body body content-type-str)))

(s/fdef piped-streams
  :args (s/cat)
  :ret (s/tuple #(instance? OutputStream %)
                #(instance? InputStream %)))

(defn piped-streams
  "Create an output stream and an input stream to which it is piped"
  []
  (let [^PipedOutputStream posh (new PipedOutputStream)]
    [posh
     (new PipedInputStream posh)]))

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
  (let [[posh pish] (piped-streams)]
    (future
      (with-open [^Writer posh-w (io/writer posh)]
        (.write posh-w (str "--"
                            boundary
                            crlf
                            "Content-Type:application/json"
                            crlf
                            crlf
                            ))
        (json/generate-stream statements posh-w)
        ;; Flush to notify
        (.flush posh-w)
        (doseq [{:keys [sha2 contentType ^File tempfile]} attachments]
          (.write posh-w (str crlf
                              "--"
                              boundary
                              crlf
                              (format "Content-Type:%s" contentType)
                              crlf
                              "Content-Transfer-Encoding:binary"
                              crlf
                              (format "X-Experience-API-Hash:%s" sha2)
                              crlf
                              crlf))
          (io/copy tempfile posh-w)
          ;; flush after each part
          (.flush posh-w))
        (.write posh-w
                (str crlf
                     "--"
                     boundary
                     "--"))))
    pish))

;; https://stackoverflow.com/a/67545577/3532563
(defn gen-boundary
  "Generate a multipart boundary"
  []
  (apply str (repeatedly 64 #(rand-nth "abcdefghijklmnopqrstuvwxyz0123456789"))))

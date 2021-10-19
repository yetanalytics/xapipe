(ns com.yetanalytics.xapipe.event
  "Internal event passed through xapipe.
  Holds xAPI data and possibly errors."
  (:require [clojure.spec.alpha :as s]
            [com.yetanalytics.xapipe.client :as client]
            [com.yetanalytics.xapipe.job.state :as state]
            [com.yetanalytics.xapipe.xapi :as xapi]))

;; A batch of statements w/attachments
(s/def ::statements (s/every ::xapi/source-statement))

;; Errors, will stop a running pipe after any good statements are sent + logged
(s/def ::errors ::state/errors)

;; Cursor represents the last point at which we're up to date.
;; Can be omitted if there is no update
(s/def ::cursor (s/nilable ::state/cursor))

(def event-spec
  (s/keys :req-un [::statements
                   ::errors]
          :opt-un [::cursor]))

(s/fdef get->event
  :args (s/cat :get-ret ::client/get-ret)
  :ret event-spec)

(defn get->event
  "Process a get response/error into an event"
  [[tag x]]
  (case tag
    :response
    {:cursor (::client/last-stored x)
     :statements (xapi/response->statements x)
     :errors []}
    :exception
    {:statements []
     :errors [{:message (ex-message x)
               :type    :source}]}))

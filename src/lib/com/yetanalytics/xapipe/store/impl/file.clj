(ns com.yetanalytics.xapipe.store.impl.file
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.yetanalytics.xapipe.store :as store])
  (:import [java.io File PushbackReader]))

(defn read-file
  "Return file edn or nil"
  [f]
  (try
    (with-open [rdr (PushbackReader. (io/reader f))]
      (edn/read rdr))
    (catch Exception _
      nil)))

(defn write-file
  "Write file and return true or false"
  [f content]
  (try
    (with-open [w (io/writer f)]
      (binding [*print-length* false
                *out* w]
        (pr content)))
    true
    (catch Exception _
      false)))

(defn delete-file
  "Delete file and return true or false"
  [f]
  (.delete ^File (io/file f)))

(defn file-path
  [dirpath job-id]
  (format "%s/%s.edn"
          dirpath
          job-id))

(defn ensure-directory
  [dirpath]
  (let [^File f (io/file dirpath)]
    (or (and (.exists f)
             (.isDirectory f))
        (.mkdir f))))

(deftype FileStore [dirpath]
  store/XapipeStore
  (read-job [store job-id]
    (read-file (file-path dirpath job-id)))
  (write-job [store {job-id :id
                     :as job}]
    (write-file (file-path dirpath job-id) job))
  (list-jobs [store]
    (for [^File f (file-seq (io/file dirpath))
          :when (and (not (.isDirectory f))
                     (.endsWith (.getName f) ".edn"))]
      (read-file f)))
  (delete-job [store job-id]
    (delete-file (file-path dirpath job-id))))

(defn new-store
  "Make a new in-memory store"
  [& [dirpath]]
  (let [dirpath (or dirpath "store")]
    (when-not (ensure-directory dirpath)
      (throw (ex-info (format "Couldn't create directory %s" dirpath)
                      {:type ::cannot-create-dir})))
    (->FileStore dirpath)))

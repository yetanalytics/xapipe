(ns com.yetanalytics.xapipe.test-support
  (:require [clojure.java.io :as io]
            [com.yetanalytics.datasim.input :as dsinput]
            [com.yetanalytics.datasim.sim :as dsim]
            [com.yetanalytics.lrs.impl.memory :as mem :refer [new-lrs]]
            [com.yetanalytics.lrs.pedestal.routes :refer [build]]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [com.yetanalytics.lrs.xapi.statements :as ss]
            [com.yetanalytics.lrs.xapi.document   :as doc]
            [io.pedestal.http :as http])
  (:import [java.net ServerSocket]))

;; https://gist.github.com/apeckham/78da0a59076a4b91b1f5acf40a96de69
(defn- get-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

;; Function to take dumped state and convert files to vectors of numbers
(defn spit-lrs-state
  [path state]
  (spit path
        (let [{attachments :state/attachments
               documents :state/documents
               :as state} state]
          (assoc state
                 :state/attachments
                 (reduce-kv
                  (fn [m sha2 att]
                    (assoc m sha2
                           (update att :content
                                   #(into [] (.getBytes (slurp %))))))
                  {}
                  attachments)
                 :state/documents
                 (into {}
                       (for [[ctx-key docs-map] documents]
                         [ctx-key
                          (into
                           {}
                           (for [[doc-id doc] docs-map]
                             [doc-id
                              (update doc :contents
                                      #(into [] (.getBytes (slurp %))))]))]))))))

;; A version of mem/fixture-state* that allows any path
(defn fixture-state
  "Get the state of an LRS from a file"
  [path]
  (-> (io/file path)
      slurp
      read-string
      (update :state/statements
              (partial conj (ss/statements-priority-map)))
      (update :state/attachments
              #(reduce-kv
                (fn [m sha2 att]
                  (assoc m sha2 (update att :content byte-array)))
                {}
                %))
      (update :state/documents
              (fn [docs]
                (into {}
                      (for [[ctx-key docs-map] docs]
                        [ctx-key
                         (into
                          (doc/documents-priority-map)
                          (for [[doc-id doc] docs-map]
                            [doc-id (update doc :contents byte-array)]))]))))))

(defn lrs
  "Make a new LRS at port, seeding from file if seed-path is present.
  Returns a map of:
  :port - For debugging
  :lrs - The LRS itself
  :start - A function of no args that will start the LRS
  :stop - A function of no args that will stop the LRS
  :dump - A function of no args that will dump memory LRS state
  :request-config - A request config ala xapipe.client"
  [port & [seed-path]]
  (let [lrs (new-lrs
             (cond->
                 {:mode :sync}
               (not-empty seed-path) (assoc :init-state (fixture-state seed-path))))
        service
        {:env                   :dev
         :lrs                   lrs
         ::http/join?           false
         ::http/allowed-origins {:creds           true
                                 :allowed-origins (constantly true)}
         ::http/routes          (build {:lrs lrs})
         ::http/resource-path   "/public"
         ::http/type            :jetty

         ::http/host              "0.0.0.0"
         ::http/port              port
         ::http/container-options {:h2c? true
                                   :h2?  false
                                   :ssl? false}}
        server (-> service
                   i/xapi-default-interceptors
                   http/create-server)]
    {:lrs            lrs
     :port           port
     :start          #(http/start server)
     :stop           #(http/stop server)
     :dump           #(mem/dump lrs)
     :request-config {:url-base    (format "http://0.0.0.0:%d" port)
                      :xapi-prefix "/xapi"}}))

(def ^:dynamic *source-lrs* nil)
(def ^:dynamic *target-lrs* nil)

(defn source-target-fixture
  "Populate *source-lrs* and *target-lrs* with started LRSs on two free ports.
  LRSs are empty by default unless seed-path is provided"
  ([f]
   (source-target-fixture nil f))
  ([seed-path f]
   (let [{start-source :start
          stop-source :stop
          :as source} (if seed-path
                        (lrs (get-free-port) seed-path)
                        (lrs (get-free-port)))
         {start-target :start
          stop-target :stop
          :as target} (lrs (get-free-port))]
     (try
       ;; Start Em Up!
       (start-source)
       (start-target)
       (binding [*source-lrs* source
                 *target-lrs* target]
         (f))
       (finally
         (stop-source)
         (stop-target))))))

(defn lrs-count
  "Get the number of statements in an LRS"
  [{:keys [dump]}]
  (-> (dump)
      :state/statements
      count))

(defn lrs-ids
  "Get all ids in an LRS"
  [{:keys [dump]}]
  (-> (dump)
      :state/statements
      keys))

(defn lrs-stored-range
  [{:keys [dump]}]
  (if-let [ss (-> (dump)
                  (get :state/statements)
                  vals
                  (not-empty))]
    [(-> ss last (get "stored"))
     (-> ss first (get "stored"))]
    []))

(defn gen-statements
  "Generate n statements with default profile and settings, or use provided"
  [n & {:keys [profiles
               personae
               alignments
               parameters]}]
  (take n
        (dsim/sim-seq
         (dsinput/map->Input
          (assoc
           (dsinput/realize-subobjects
            {:personae
             (or personae
                 [{:name "Test Subjects",
                   :objectType "Group",
                   :member
                   [{:name "alice",
                     :mbox "mailto:alice@example.org",
                     :objectType "Agent"}
                    {:name "bob",
                     :mbox "mailto:bob@example.org",
                     :objectType "Agent"}]}])
             :parameters (or parameters
                             {:from "2021-10-28T20:07:36.035431Z"
                              :seed 42})
             :alignments (or alignments [])})
           :profiles
           (into []
                 (map
                  (fn [loc]
                    (dsinput/from-location
                     :profile :json loc))
                  (or profiles
                      ["dev-resources/profiles/calibration.jsonld"]))))))))

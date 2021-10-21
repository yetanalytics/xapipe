(ns com.yetanalytics.xapipe.test-support
  (:require [io.pedestal.http :as http]
            [com.yetanalytics.lrs.impl.memory :as mem :refer [new-lrs]]
            [com.yetanalytics.lrs.pedestal.routes :refer [build]]
            [com.yetanalytics.lrs.pedestal.interceptor :as i]
            [com.yetanalytics.lrs.test-runner :as test-runner])
  (:import [java.net ServerSocket]))

;; https://gist.github.com/apeckham/78da0a59076a4b91b1f5acf40a96de69
(defn- get-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn lrs
  "Make a new LRS at port.
  Returns a map of:
  :port - For debugging
  :lrs - The LRS itself
  :start - A function of no args that will start the LRS
  :stop - A function of no args that will stop the LRS
  :dump - A function of no args that will dump memory LRS state
  :request-config - A request config ala xapipe.client"
  [port]
  (let [lrs (new-lrs {}) ;; TODO: Figure out why sync fails
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
  Runs the conformance test to "
  [f]
  (let [{start-source :start
         stop-source :stop
         :as source} (lrs (get-free-port))
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
        (stop-target)))))

;; Utilities for easing LRS usage and introspection
(defn seed-conf-tests!
  "Seed the LRS with conformance-tests, throws on fail.
  Must be run from within an existing test suite, that should be in a fixture"
  [{{:keys [url-base xapi-prefix]} :request-config}]
  (when-not (true? (test-runner/conformant?
                    "-e" (str url-base xapi-prefix)))
    (throw (ex-info "Seed failed, LRS is not conformant!"
                    {:type ::seed-fail}))))

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

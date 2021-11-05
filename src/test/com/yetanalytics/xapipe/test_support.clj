(ns com.yetanalytics.xapipe.test-support
  (:require [clojure.test :as test]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [clojure.string :as cs]
            [clojure.template :as temp]
            [clojure.tools.logging :as log]
            [com.yetanalytics.datasim.input :as dsinput]
            [com.yetanalytics.datasim.sim :as dsim]
            [com.yetanalytics.lrs :as lrs]
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
  :load - A function of two args, statements and attachments to load data
  :request-config - A request config ala xapipe.client"
  [& {:keys [seed-path
             port]}]
  (let [port (or port
                 (get-free-port))
        lrs (new-lrs
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
     :start          #(do
                        (log/debugf "Starting LRS on port %d" port)
                        (http/start server))
     :stop           #(do
                        (log/debugf "Stopping LRS on port %d" port)
                        (http/stop server))
     :dump           #(mem/dump lrs)
     :load           (fn [statements & [attachments]]
                       (lrs/store-statements
                        lrs
                        {}
                        (into [] statements)
                        (into [] attachments)))
     :request-config {:url-base    (format "http://0.0.0.0:%d" port)
                      :xapi-prefix "/xapi"}}))

(defmacro with-running
  "bindings => [name lrs ...]

  Evaluates body in a try expression with names bound to the values
  of the lrss, starts them, and finally stops them each in reverse order."
  [bindings & body]
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                ((:start ~(bindings 0)))
                                (with-running ~(subvec bindings 2) ~@body)
                                (finally
                                  ((:stop ~(bindings 0))))))
    :else (throw (IllegalArgumentException.
                  "with-open only allows Symbols in bindings"))))

(def ^:dynamic *source-lrs* nil)
(def ^:dynamic *target-lrs* nil)

(defn source-target-fixture
  "Populate *source-lrs* and *target-lrs* with started LRSs on two free ports.
  LRSs are empty by default unless seed-path is provided"
  ([f]
   (source-target-fixture {} f))
  ([{:keys [seed-path]} f]
   (let [{start-source :start
          stop-source :stop
          :as source} (if seed-path
                        (lrs :seed-path seed-path)
                        (lrs))
         {start-target :start
          stop-target :stop
          :as target} (lrs)]
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

(defn lrs-statements
  "Get all ids in an LRS"
  [{:keys [dump]}]
  (-> (dump)
      :state/statements
      vals
      reverse))

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

(defn instrument-fixture
  ([]
   (instrument-fixture
    (remove
     (fn [sym]
       (let [sym-ns (namespace sym)]
         (or
          ;; Datasim and LRS are only used in testing, and are not called
          ;; by the lib or cli.
          ;; Therefore we can omit them.
          (cs/starts-with? sym-ns "com.yetanalytics.datasim")
          (cs/starts-with? sym-ns "com.yetanalytics.lrs"))))
     (st/instrumentable-syms))))
  ([sym-or-syms]
   (fn [f]
     (st/instrument sym-or-syms)
     (try
       (f)
       (finally
         (st/unstrument sym-or-syms))))))

(defmacro art
  "Like clojure.test/are, but without the is.

  Example: (art [x y]
                (is (= x y))
                2 (+ 1 1)
                4 (* 2 2))
  Expands to:
           (do (is (= 2 (+ 1 1)))
               (is (= 4 (* 2 2))))"
  [argv expr & args]
  (if (or
       ;; (are [] true) is meaningless but ok
       (and (empty? argv) (empty? args))
       ;; Catch wrong number of args
       (and (pos? (count argv))
            (pos? (count args))
            (zero? (mod (count args) (count argv)))))
    `(temp/do-template ~argv ~expr ~@args)
    (throw (IllegalArgumentException. "The number of args doesn't match are's argv."))))

;; Spec Auto-testing fns from LRS
(alias 'stc 'clojure.spec.test.check)

(def stc-ret :clojure.spec.test.check/ret)

(def stc-opts :clojure.spec.test.check/opts)

(defn failures [check-results]
  (mapv
   (fn [{:keys [sym] :as x}]
     [sym (-> x
              (update :spec s/describe)
              (dissoc :sym)
              ;; Dissoc the top level trace, leave the shrunken one
              (update stc-ret dissoc :result-data))])
   (remove #(-> %
                stc-ret
                :result
                true?)
           check-results)))

(defmacro deftest-check-ns
  "Check all instrumented symbols in an ns. A map of overrides
      can provide options & a default:
      {`foo     {::stc/ret {:num-tests 100}}
       :default {::stc/ret {:num-tests 500}}}"
  [test-sym ns-sym & [overrides]]
  (let [default-opts# (get overrides :default {})
        overrides#    (into {} ; Qualified overrides
                            (map
                             (fn [[fn-sym fn-opts]]
                               [(symbol (name ns-sym) (name fn-sym))
                                fn-opts])
                             (or (dissoc overrides :default) {})))
        syms-opts#    (into {}
                            (map (fn [fn-sym]
                                   [fn-sym
                                    (get overrides# fn-sym default-opts#)]))
                            (st/enumerate-namespace ns-sym))]
    `(test/deftest ~test-sym
       ~@(for [[sym opts] syms-opts#]
           `(test/testing ~(name sym)
              (test/is
               ~(list 'empty?
                      `(failures (st/check (quote ~sym) ~opts)))))))))

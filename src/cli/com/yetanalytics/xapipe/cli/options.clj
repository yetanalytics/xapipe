(ns com.yetanalytics.xapipe.cli.options
  "clojure.tools.cli options for xapipe CLI"
  (:require [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.cli :as cli]
            [com.yetanalytics.xapipe :as xapipe]
            [com.yetanalytics.xapipe.job.json :as job-json]
            [com.yetanalytics.xapipe.filter.path :as fpath]))

(defn option-spec->spec-def
  [[short-command long-command desc
    & {:keys [id
              default
              parse-fn
              multi
              validate]}]
   overrides]
  (let [[long-command-name
         ?argname] (-> long-command
                       (subs 2)
                       (cs/split #"\s"))
        option-name (or (and id (name id))
                        long-command-name)
        spec-kw (keyword (str (ns-name *ns*))
                         option-name)
        bool? (not ?argname)
        override-spec (get overrides spec-kw)
        spec (cond
               override-spec
               override-spec

               (and multi default)
               (case default
                 [] `(s/every string?)
                 {} `map?)

               (and parse-fn validate)
               `(s/and
                 (s/conformer (fn [x#]
                                (try
                                  (~parse-fn x#)
                                  (catch Exception _#
                                    x#))))
                 ~(first validate))

               validate
               `(s/and
                 string?
                 ~(first validate))

               bool?
               `boolean?
               :else
               `string?)
        arg-spec `#{~(format "--%s" long-command-name)
                    ~@(if short-command
                        [short-command]
                        [])}]
    `(list
      ;; actual key spec
      (s/def ~spec-kw
        ~spec)
      ;; Option Sequence spec
      ~(if bool?
         `(s/cat :arg ~arg-spec)
         `(s/cat :arg ~arg-spec
                 :val (s/and string?
                             not-empty))))))

(defmacro def-option-specs
  "Given a vector of option-specs, def specs for each option and a summative map
  spec"
  [options-sym
   & [overrides]]
  (let [overrides (or overrides {})
        options @(resolve options-sym)
        spec-defs-and-args (map #(option-spec->spec-def % overrides)
                                options)
        spec-defs (map second spec-defs-and-args)
        arg-specs (map #(nth % 2) spec-defs-and-args)
        spec-keys (map second spec-defs)]
    `(do
       ;; Write out the defs
       ~@spec-defs
       ;; followed by a map spec
       (s/def ~(keyword (str (ns-name *ns*)) (name options-sym))
         (s/keys :opt-un ~(into []
                                spec-keys)))
       ;; followed by an args spec
       (s/def ~(keyword (str (ns-name *ns*)) (format "%s-args"
                                                     (name options-sym)))
         (s/alt
          ~@(mapcat
             (fn [k spec]
               [(keyword nil (name k))
                spec])
             spec-keys
             arg-specs))))))

(def storage-options
  [["-s" "--storage STORAGE" "Select storage backend, file (default), redis or noop, mem is for testing only"
    :default :file
    :parse-fn #(keyword nil (name %))
    :validate [#{:noop
                 :redis
                 :mem
                 :file} "Must be: noop | redis | mem | file"]]
   ;; Redis Backend Options
   [nil "--redis-uri URI" "Redis Connection URI"
    :default "redis://0.0.0.0:6379"]
   [nil "--redis-prefix PREFIX" "Redis key prefix"
    :default "xapipe"]
   [nil "--file-store-dir PATH" "Directory path for filesystem storage"
    :default "store"]])

(def metrics-options
  [[nil "--metrics-reporter REPORTER" "Select a metrics reporter, noop (default) or prometheus"
    :default "noop"
    :validate [#{"noop"
                 "prometheus"} "Must be: noop | prometheus"]]
   [nil "--prometheus-push-gateway URL" "Address of prometheus push gateway server"
    :default "0.0.0.0:9091"]])

(def common-options
  (into
   [["-h" "--help" "Show the help and options summary"
     :default false]
    [nil "--job-id ID" "Job ID"]
    ;; Connection Manager Options
    [nil "--conn-timeout TIMEOUT" "Connection Manager Connection Timeout"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--conn-threads THREADS" "Connection Manager Max Threads"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--conn-default-per-route CONNS" "Connection Manager Simultaneous Connections Per Host"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--conn-insecure?" "Allow Insecure HTTPS Connections"]
    [nil "--conn-io-thread-count THREADS" "Connection Manager I/O Thread Pool Size, default is number of processors"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]]
    [nil "--show-job" "Show the job and exit"
     :default false]
    [nil "--list-jobs" "List jobs in persistent storage"
     :default false]
    [nil "--delete-job ID" "Delete the job specified and exit."]
    ["-f" "--force-resume" "If resuming a job, clear any errors and force it to resume."
     :default false]
    [nil "--json JSON" "Take a job specification as a JSON string"
     :parse-fn job-json/json->job]
    [nil "--json-file FILE" "Take a job specification from a JSON file"
     :parse-fn (fn [filepath]
                 (-> filepath
                     slurp
                     job-json/json->job))]
    [nil "--json-out FILE" "Write JOB to a JSON file"]]
   (concat storage-options
           metrics-options)))

(def-option-specs common-options {::json ::xapipe/job
                                  ::json-file ::xapipe/job})

(defn backoff-opts
  [tag]
  [[nil
    (format "--%s-backoff-budget BUDGET" tag)
    (format "%s LRS Retry Backoff Budget in ms" (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 10000]
   [nil
    (format "--%s-backoff-max-attempt MAX" tag)
    (format "%s LRS Retry Backoff Max Attempts, set to -1 for no retry"
            (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [#(<= -1 %) "Must be -1 or greater"]
    :default 10]
   [nil
    (format "--%s-backoff-j-range RANGE" tag)
    (format "%s LRS Retry Backoff Jitter Range in ms" (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [nat-int? "Must be a natural integer"]]
   [nil
    (format "--%s-backoff-initial INITIAL" tag)
    (format "%s LRS Retry Backoff Initial Delay" (cs/capitalize tag))
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]]])

(defn oauth-opts
  [tag]
  [[nil
    (format "--%s-auth-uri URI" tag)
    (format "%s LRS OAuth autentication URI" (cs/capitalize tag))]
   [nil
    (format "--%s-client-id ID" tag)
    (format "%s LRS OAuth client ID" (cs/capitalize tag))]
   [nil
    (format "--%s-client-secret SECRET" tag)
    (format "%s LRS OAuth client secret" (cs/capitalize tag))]
   [nil
    (format "--%s-scope SCOPE" tag)
    (format "%s LRS OAuth scope" (cs/capitalize tag))]
   [nil
    (format "--%s-token TOKEN" tag)
    (format "%s LRS OAuth Bearer token" (cs/capitalize tag))]])

;; a set for filtering
(def valid-get-params
  #{:agent
    :verb
    :activity
    :registration
    :related_activities
    :related_agents
    :since
    :until
    :format})

(def source-options
  (into
   [[nil "--source-url URL" "Source LRS xAPI Endpoint"
     :validate [not-empty "Source LRS URL Required"]]
    [nil "--source-batch-size SIZE" "Source LRS GET limit param"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]
     :default 50]
    [nil "--source-poll-interval INTERVAL" "Source LRS GET poll timeout"
     :parse-fn #(Long/parseLong %)
     :validate [pos-int? "Must be a positive integer"]
     :default 1000]
    ["-p" "--xapi-get-param KEY=VALUE" "xAPI GET Parameters"
     :id :get-params
     :default {}
     :multi true
     :validate [not-empty "Must provide param KEY=VALUE"]
     :update-fn
     (fn [m v]
       (let [[k v] (cs/split v #"=" 2)
             kk (keyword k)]
         (if (contains? valid-get-params
                        kk)
           (assoc m kk
                  (if (#{:related_agents
                         :related_activities} kk)
                    (Boolean/parseBoolean v)
                    v))
           m)))]
    [nil "--source-username USERNAME" "Source LRS BASIC Auth username"]
    [nil "--source-password PASSWORD" "Source LRS BASIC Auth password"]
    [nil "--json-only" "Only operate in JSON statement mode for data transfer, ignoring Attachments/multipart (for compatibility issues)"
     :default false]]
   (concat
    (oauth-opts "source")
    (backoff-opts "source"))))

(def-option-specs source-options)

(def target-options
  (into [[nil "--target-url URL" "Target LRS xAPI Endpoint"
          :validate [not-empty "Target LRS URL Required"]]
         [nil "--target-batch-size SIZE" "Target LRS POST desired batch size"
          :parse-fn #(Long/parseLong %)
          :validate [pos-int? "Must be a positive integer"]
          :default 50]
         [nil "--target-username USERNAME" "Target LRS BASIC Auth username"]
         [nil "--target-password PASSWORD" "Target LRS BASIC Auth password"]]
        (concat
         (oauth-opts "target")
         (backoff-opts "target"))))

(def-option-specs target-options)

(def job-options
  [[nil "--get-buffer-size SIZE" "Size of GET response buffer"
    :parse-fn #(Long/parseLong %)
    :validate [nat-int? "Must be a natural integer"]
    :default 10]
   [nil "--batch-timeout TIMEOUT" "Msecs to wait for a fully formed batch"
    :parse-fn #(Long/parseLong %)
    :validate [pos-int? "Must be a positive integer"]
    :default 200]
   ;; Filter options
   [nil "--template-profile-url URL" "Profile URL/location from which to apply statement template filters"
    :id :filter-template-profile-urls
    :multi true
    :default []
    :update-fn conj]
   [nil "--template-id IRI" "Statement template IRIs to filter on"
    :id :filter-template-ids
    :multi true
    :default []
    :update-fn conj]
   [nil "--pattern-profile-url URL" "Profile URL/location from which to apply statement pattern filters"
    :id :filter-pattern-profile-urls
    :multi true
    :default []
    :update-fn conj]
   [nil "--pattern-id IRI" "Pattern IRIs to filter on"
    :id :filter-pattern-ids
    :multi true
    :default []
    :update-fn conj]
   [nil "--ensure-path JSONPATH" "A JSONPath expression used to filter statements to only those with data at the given path"
    :id :filter-ensure-paths
    :multi true
    :default []
    :update-fn (fn [coll v]
                 (conj coll
                       (fpath/parse-path v)))]
   [nil "--match-path JSONPATH=JSON" "A JSONPath expression and matching value used to filter statements to only those with data matching the value at the given path"
    :id :filter-match-paths
    :multi true
    :default []
    :update-fn
    (fn [coll v]
      (let [[path match-str] (cs/split v #"=" 2)
            ;; Attempt to parse to json, but if you can't it's just a string
            match-v (try (json/parse-string match-str)
                         (catch Exception _
                           match-str))]
        (conj coll
              [(fpath/parse-path path)
               match-v])))]
   [nil "--concept-profile-url IRI" "Profile URL/location from which to apply concept filters"
    :id :filter-concept-profile-urls
    :multi true
    :default []
    :update-fn conj]
   [nil "--concept-type CONCEPT-TYPE" "Specific type of concept to filter on. If not set, it will match all concepts in the Profile."
    :id :filter-concept-types
    :multi true
    :validate [#(some #{%} #{"Verb" "ActivityType" "AttachmentUsageType"}) "Must be either 'Verb' or 'ActivityType' or 'AttachmentUsageType'"]
    :default []
    :update-fn conj]
   [nil "--activity-type-id IRI" "Activity Type IRIs to filter on. If left blank it will match all Activity Types in the Profile"
    :id :filter-activity-type-ids
    :multi true
    :default []
    :update-fn conj]
   [nil "--verb-id IRI" "Verb IRIs to filter on. If left blank it will match all Verbs in the Profile"
    :id :filter-verb-ids
    :multi true
    :default []
    :update-fn conj]
   [nil "--attachment-usage-type IRI" "Attachment Usage Type IRIs to filter on. If left blank it will match all Attachment usage types in the Profile"
    :id :filter-attachment-usage-types
    :multi true
    :default []
    :update-fn conj]

   [nil "--statement-buffer-size SIZE" "Desired size of statement buffer"
    :parse-fn #(Long/parseLong %)
    :validate [nat-int? "Must be a natural integer"]]
   [nil "--batch-buffer-size SIZE" "Desired size of statement batch buffer"
    :parse-fn #(Long/parseLong %)
    :validate [nat-int? "Must be a natural integer"]]
   [nil "--cleanup-buffer-size SIZE" "Desired size of tempfile cleanup buffer"
    :parse-fn #(Long/parseLong %)
    :validate [nat-int? "Must be a natural integer"]]])

(def-option-specs job-options {::filter-ensure-paths ::fpath/ensure-paths
                               ::filter-match-paths ::fpath/match-paths})

(s/def ::all-options
  (s/merge ::common-options
           ::source-options
           ::target-options
           ::job-options))

(s/def ::all-args
  (s/*
   (s/alt :common ::common-options-args
          :source ::source-options-args
          :target ::target-options-args
          :job    ::job-options-args)))

(s/def ::no-defaults boolean?)

(s/fdef args->options
  :args (s/cat :args (s/spec ::all-args)
               :kwargs (s/keys* :opt-un [::no-defaults]))
  :ret ::all-options)

(defn args->options
  [args
   & {:keys [no-defaults]
      :or {no-defaults false}}]
  (let [{:keys [errors]
         :as ret} (cli/parse-opts args
                                  (concat
                                   common-options
                                   source-options
                                   target-options
                                   job-options)
                                  :no-defaults no-defaults)]
    (if (not-empty errors)
      (throw (ex-info (format "Options Error: %s"
                              (cs/join \, errors))
                      {:type ::options-error
                       :ret ret}))
      ret)))

[<- Back to Index](index.md)
# LRSPipe Options Reference

Below is an reference and explanation of all options for running LRSPipe. This reference can be accessed from the CLI at any time by running `bin/run.sh --help`.

## CLI Options

```
Run a new job:
    --source-url http://0.0.0.0:8080/xapi --target-url http://0.0.0.0:8081/xapi

Resume a paused job:
    --job-id <id>

Force Resume a job with errors:
    --job-id <id> -f

List All Jobs:
    --list-jobs

Delete a Job:
    --delete-job <id>

All options:
  -h, --help                                                  Show the help and options summary
      --job-id ID                                             Job ID
      --conn-timeout TIMEOUT                                  Connection Manager Connection Timeout
      --conn-threads THREADS                                  Connection Manager Max Threads
      --conn-default-per-route CONNS                          Connection Manager Simultaneous Connections Per Host
      --conn-insecure?                                        Allow Insecure HTTPS Connections
      --conn-io-thread-count THREADS                          Connection Manager I/O Thread Pool Size, default is number of processors
      --show-job                                              Show the job and exit
      --list-jobs                                             List jobs in persistent storage
      --delete-job ID                                         Delete the job specified and exit.
  -f, --force-resume                                          If resuming a job, clear any errors and force it to resume.
      --json JSON                                             Take a job specification as a JSON string
      --json-file FILE                                        Take a job specification from a JSON file
      --json-out FILE                                         Write JOB to a JSON file
  -s, --storage STORAGE                 :file                 Select storage backend, file (default), redis or noop, mem is for testing only
      --redis-uri URI                   redis://0.0.0.0:6379  Redis Connection URI
      --redis-prefix PREFIX             xapipe                Redis key prefix
      --file-store-dir PATH             store                 Directory path for filesystem storage
      --metrics-reporter REPORTER       noop                  Select a metrics reporter, noop (default) or prometheus
      --prometheus-push-gateway URL     0.0.0.0:9091          Address of prometheus push gateway server
      --source-url URL                                        Source LRS xAPI Endpoint
      --source-batch-size SIZE          50                    Source LRS GET limit param
      --source-poll-interval INTERVAL   1000                  Source LRS GET poll timeout
  -p, --xapi-get-param KEY=VALUE        {}                    xAPI GET Parameters
      --source-username USERNAME                              Source LRS BASIC Auth username
      --source-password PASSWORD                              Source LRS BASIC Auth password
      --json-only                                             Only operate in JSON statement mode for data transfer, ignoring Attachments/multipart (for compatibility issues)
      --source-auth-uri URI                                   Source LRS OAuth autentication URI
      --source-client-id ID                                   Source LRS OAuth client ID
      --source-client-secret SECRET                           Source LRS OAuth client secret
      --source-scope SCOPE                                    Source LRS OAuth scope
      --source-token TOKEN                                    Source LRS OAuth Bearer token
      --source-backoff-budget BUDGET    10000                 Source LRS Retry Backoff Budget in ms
      --source-backoff-max-attempt MAX  10                    Source LRS Retry Backoff Max Attempts, set to -1 for no retry
      --source-backoff-j-range RANGE                          Source LRS Retry Backoff Jitter Range in ms
      --source-backoff-initial INITIAL                        Source LRS Retry Backoff Initial Delay
      --target-url URL                                        Target LRS xAPI Endpoint
      --target-batch-size SIZE          50                    Target LRS POST desired batch size
      --target-username USERNAME                              Target LRS BASIC Auth username
      --target-password PASSWORD                              Target LRS BASIC Auth password
      --target-auth-uri URI                                   Target LRS OAuth autentication URI
      --target-client-id ID                                   Target LRS OAuth client ID
      --target-client-secret SECRET                           Target LRS OAuth client secret
      --target-scope SCOPE                                    Target LRS OAuth scope
      --target-token TOKEN                                    Target LRS OAuth Bearer token
      --target-backoff-budget BUDGET    10000                 Target LRS Retry Backoff Budget in ms
      --target-backoff-max-attempt MAX  10                    Target LRS Retry Backoff Max Attempts, set to -1 for no retry
      --target-backoff-j-range RANGE                          Target LRS Retry Backoff Jitter Range in ms
      --target-backoff-initial INITIAL                        Target LRS Retry Backoff Initial Delay
      --get-buffer-size SIZE            10                    Size of GET response buffer
      --batch-timeout TIMEOUT           200                   Msecs to wait for a fully formed batch
      --template-profile-url URL        []                    Profile URL/location from which to apply statement template filters
      --template-id IRI                 []                    Statement template IRIs to filter on
      --pattern-profile-url URL         []                    Profile URL/location from which to apply statement pattern filters
      --pattern-id IRI                  []                    Pattern IRIs to filter on
      --ensure-path JSONPATH            []                    A JSONPath expression used to filter statements to only those with data at the given path
      --match-path JSONPATH=JSON        []                    A JSONPath expression and matching value used to filter statements to only those with data matching the value at the given path
      --concept-profile-url IRI         []                    Profile URL/location from which to apply concept filters
      --concept-type CONCEPT-TYPE       []                    Specific type of concept to filter on. If not set, it will match all concepts in the Profile.
      --activity-type-id IRI            []                    Activity Type IRIs to filter on. If left blank it will match all Activity Types in the Profile
      --verb-id IRI                     []                    Verb IRIs to filter on. If left blank it will match all Verbs in the Profile
      --attachment-usage-type IRI       []                    Attachment Usage Type IRIs to filter on. If left blank it will match all Attachment usage types in the Profile
      --statement-buffer-size SIZE                            Desired size of statement buffer
      --batch-buffer-size SIZE                                Desired size of statement batch buffer
      --cleanup-buffer-size SIZE                              Desired size of tempfile cleanup buffer
```

[<- Back to Index](index.md)

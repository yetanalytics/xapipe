# xapipe
Pipe data between conformant xAPI Learning Record Stores

## Usage

### Start a New Job

``` shell
clojure -Mcli -m com.yetanalytics.xapipe.main \
    --source-url http://0.0.0.0:8080/xapi \
    --target-url http://0.0.0.0:8081/xapi \
    --job-id myjob
```

### Resume a Paused Job
``` shell
clojure -M:cli -m com.yetanalytics.xapipe.main --job-id myjob
```

### Force-Resume a Job with Errors

``` shell
clojure -M:cli -m com.yetanalytics.xapipe.main --job-id myjob -f
```

### List Persisted Jobs

``` shell
clojure -Mcli -m com.yetanalytics.xapipe.main --list-jobs -s redis

Nov 03, 2021 4:41:48 PM com.yetanalytics.xapipe.cli invoke
INFO: Page 0
|                               job-id | status |               cursor |
|--------------------------------------+--------+----------------------|
| d24de6cc-ade6-48e9-a23c-c7ee48ed53f9 |  error | 1970-01-01T00:00:00Z |
```

### Delete Job

``` shell
clojure -Mcli -m com.yetanalytics.xapipe.main --delete-job myjob
```

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
  -s, --storage STORAGE                 :noop                 Select storage backend, noop (default) or redis, mem is for testing only
      --redis-uri URI                   redis://0.0.0.0:6379  Redis Connection URI
      --redis-prefix PREFIX                                   Redis key prefix
      --file-store-dir PATH             store                 Directory path for filesystem storage
      --source-url URL                                        Source LRS xAPI Endpoint
      --source-batch-size SIZE          50                    Source LRS GET limit param
      --source-poll-interval INTERVAL   1000                  Source LRS GET poll timeout
  -p, --xapi-get-param KEY=VALUE        {}                    xAPI GET Parameters
      --source-username USERNAME                              Source LRS BASIC Auth username
      --source-password PASSWORD                              Source LRS BASIC Auth password
      --source-backoff-budget BUDGET    10000                 Source LRS Retry Backoff Budget in ms
      --source-backoff-max-attempt MAX  10                    Source LRS Retry Backoff Max Attempts, set to -1 for no retry
      --source-backoff-j-range RANGE                          Source LRS Retry Backoff Jitter Range in ms
      --source-backoff-initial INITIAL                        Source LRS Retry Backoff Initial Delay
      --target-url URL                                        Target LRS xAPI Endpoint
      --target-batch-size SIZE          50                    Target LRS POST desired batch size
      --target-username USERNAME                              Target LRS BASIC Auth username
      --target-password PASSWORD                              Target LRS BASIC Auth password
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
      --statement-buffer-size SIZE                            Desired size of statement buffer
      --batch-buffer-size SIZE                                Desired size of statement batch buffer
```

## Docker

### Invocation

Start a job with a persistent volume to store job state:

``` shell
docker run -v xapipe:/xapipe/store -it yetanalytics/xapipe \
    --source-url http://host.docker.internal:8080/xapi \
    --target-url http://host.docker.internal:8081/xapi \
    --job-id myjob
```

Stop the job with `^C`. You can then resume it:

``` shell
docker run -v xapipe:/xapipe/store -it yetanalytics/xapipe --job-id myjob
```

### Demo

This repo includes a Docker Compose file at [`demo/docker-compose.yml`](demo/docker-compose.yml) that creates source and target LRS instances using [SQL LRS](https://github.com/yetanalytics/lrsql) and uses `xapipe` to forward data between them.

To run the demo:

``` shell
cd demo
docker compose up
```

This will create a source LRS at `http://0.0.0.0:8080` and a target LRS at `http://0.0.0.0:8081`. If you send xAPI data to the source it will be forwarded to the target.

## License

Copyright © 2021 Yet Analytics, Inc.

Distributed under the Apache License version 2.0.

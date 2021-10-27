# xapipe
Pipe data between conformant xAPI Learning Record Stores

## Usage

### Start a New Job

``` shell
clojure -Mcli -m com.yetanalytics.xapipe.main start http://0.0.0.0:8080/xapi http://0.0.0.0:8081/xapi
```

### Resume a Paused Job (Redis Only)

``` shell
clojure -M:cli -m com.yetanalytics.xapipe.main resume c3e3a1a5-2220-4fbc-8b51-bd0618e35f95 -s redis
```

### Retry a Job with Errors (Redis Only)

``` shell
clojure -M:cli -m com.yetanalytics.xapipe.main retry c3e3a1a5-2220-4fbc-8b51-bd0618e35f95 -s redis
```

## CLI Options

```
start <source-url> <target-url> & options:
  -h, --help                                     Show the help and options summary
  -s, --storage STORAGE                 :noop    Select storage backend, noop (default) or redis
      --redis-host HOST                 0.0.0.0  Redis Host
      --redis-port PORT                 6379     Redis Port
      --get-buffer-size SIZE            10       Size of GET response buffer
      --get-proc-conc SIZE              1        Concurrency of get req processing
      --batch-timeout TIMEOUT           200      Msecs to wait for a fully formed batch
      --job-id ID                                Job ID
      --statement-buffer-size SIZE               Desired size of statement buffer
      --batch-buffer-size SIZE                   Desired size of statement batch buffer
      --show-job                                 Show the job and exit
      --source-batch-size SIZE          50       Source LRS GET limit param
      --source-poll-interval INTERVAL   1000     Source LRS GET poll timeout
  -p, --xapi-get-param KEY=VALUE        {}       xAPI GET Parameters
      --source-username USERNAME                 Source LRS BASIC Auth username
      --source-password PASSWORD                 Source LRS BASIC Auth password
      --source-backoff-budget BUDGET    10000    Source LRS Retry Backoff Budget in ms
      --source-backoff-max-attempt MAX  10       Source LRS Retry Backoff Max Attempts, set to -1 for no retry
      --source-backoff-j-range RANGE             Source LRS Retry Backoff Jitter Range in ms
      --source-backoff-initial INITIAL           Source LRS Retry Backoff Initial Delay
      --target-batch-size SIZE          50       Target LRS POST desired batch size
      --target-username USERNAME                 Target LRS BASIC Auth username
      --target-password PASSWORD                 Target LRS BASIC Auth password
      --target-backoff-budget BUDGET    10000    Target LRS Retry Backoff Budget in ms
      --target-backoff-max-attempt MAX  10       Target LRS Retry Backoff Max Attempts, set to -1 for no retry
      --target-backoff-j-range RANGE             Target LRS Retry Backoff Jitter Range in ms
      --target-backoff-initial INITIAL           Target LRS Retry Backoff Initial Delay
resume <job-id> & options:
  -h, --help                      Show the help and options summary
  -s, --storage STORAGE  :noop    Select storage backend, noop (default) or redis
      --redis-host HOST  0.0.0.0  Redis Host
      --redis-port PORT  6379     Redis Port
      --show-job                  Show the job and exit
retry <job-id> & options:
  -h, --help                      Show the help and options summary
  -s, --storage STORAGE  :noop    Select storage backend, noop (default) or redis
      --redis-host HOST  0.0.0.0  Redis Host
      --redis-port PORT  6379     Redis Port
      --show-job                  Show the job and exit
```

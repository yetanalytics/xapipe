[<- Back to README](../README.md)
# LRSPipe Basic Usage

In this section we'll illustrate a few examples of basic usage patterns of LRSPipe.

## Basic Forwarding

### Start a New Job

In this example we are starting a basic forwarding job with no filters from a source LRS at `0.0.0.0:8080` to a target LRS at `0.0.0.0:8081`. We provide it with a `job-id` which we can reference later in the case that we need to stop, resume, or modify the job. Once initialized this job will forward all existing statements, and then remain active checking for new statements in the source LRS.

``` shell
bin/run.sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --job-id myjob \
           --source-username my_key --source-password my_secret \
           --target-username my_key --target-password my_secret
```

### Resume a Paused Job

If a job has been started in the past but is not actively running, it can be resumed using only the `job-id`. The system remembers the LRS details and how much it has already forwarded.

``` shell
bin/run.sh --job-id myjob
```

### Force-Resume a Job with Errors

In some cases, when a job has been paused due to an error, it may need to be force-resumed with the `-f` flag below.

``` shell
bin/run.sh --job-id myjob -f
```

## Forwarding with Filtering

### Statement Template Filtering

To filter statements based on xAPI Profile Statement Templates, use the `--template-profile-url` flag like so:

``` shell
bin/run.sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --job-id template-job-1 \
           --template-profile-url "../location-of-profile.jsonld" \
           --template-id "https://xapinet.org/xapi/yet/template-id-1" \
           --template-id "https://xapinet.org/xapi/yet/template-id-2" \
           --source-username my_key --source-password my_secret \
           --target-username my_key --target-password my_secret

```

The profile url value can be either a web-accessible url, such as a Profile Server, or a local file. The `--template-id` flags are optional and further limit the forwarding to only the desired Templates. If the `--template-id` flag is omitted the job will filter on all available Statement Templates in the Profile.

### Pattern Filtering

To filter statements based on xAPI Profile Patterns, use the `--pattern-profile-url` flag like so:

``` shell
bin/run.sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --job-id pattern-job-1 \
           --pattern-profile-url "../location-of-profile.jsonld" \
           --pattern-id "https://xapinet.org/xapi/yet/pattern-id-1" \
           --pattern-id "https://xapinet.org/xapi/yet/pattern-id-2" \
           --source-username my_key --source-password my_secret \
           --target-username my_key --target-password my_secret

```

As with Template Filtering, the `--pattern-id` flags are optional and further limit the forwarding to only the desired Patterns. If the `--pattern-id` flag is omitted the job will filter on all available Patterns in the Profile.

### JsonPath Presence Filtering

In cases where you want to ensure that every statement posted to the target LRS has data at a given path, use the `--ensure-path` option:

``` shell
bin/run-sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --job-id path-job-1 \
           --source-username my_key --source-password my_secret \
           --target-username my_key --target-password my_secret \
           --ensure-path $.result.score.scaled

```

Only statements with a value set at the given [JsonPath String](https://goessner.net/articles/JsonPath/) `$.result.score.scaled` will be passed to the target LRS. This simple filter is useful to ensure the density of an xAPI dataset.

If the option is passed multiple times only statements that contain data at ALL paths will be written to the target LRS.

To match a value at a given path see JsonPath Matching below. For more complex filters with features like negation use an xAPI Profile with Template and Pattern Filtering (see above).

### JsonPath Matching

You can apply simple path-matching filters to LRSPipe using the `--match-path` option:

``` shell
bin/run-sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --job-id path-match-job-1 \
           --source-username my_key --source-password my_secret \
           --target-username my_key --target-password my_secret \
           --match-path $.verb.id=http://example.com/verb

```

Only statements with the value `"http://example.com/verb"` at the path `$.verb.id` will be written to the target LRS.

If the option is given multiple times, a statement must satisfy at least one given value for each path given:

``` shell
bin/run-sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --job-id path-match-job-2 \
           --source-username my_key --source-password my_secret \
           --target-username my_key --target-password my_secret \
           --match-path $.verb.id=http://example.com/verb1 \
           --match-path $.verb.id=http://example.com/verb2

```

Statements with an `$.verb.id` of `http://example.com/verb1` OR `http://example.com/verb2` will be passed.

#### Json Values

LRSPipe will attempt to parse path matches as JSON first, then as string. This means you can match a JSON object:

``` shell
bin/run-sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --job-id path-match-job-3 \
           --source-username my_key --source-password my_secret \
           --target-username my_key --target-password my_secret \
           --match-path $.actor='{"mbox":"mailto:bob@example.com","objectType":"Agent"}'

```

Statements with an `$.actor` matching the fields provided exactly will be passed.

## Job Management

### List Persisted Jobs

To see the state of all jobs the command below can be used.

``` shell
bin/run.sh --list-jobs -s redis

Nov 03, 2021 4:41:48 PM com.yetanalytics.xapipe.cli invoke
INFO: Page 0
|                               job-id | status |               cursor           |
|--------------------------------------+--------+--------------------------------|
| d24de6cc-ade6-48e9-a23c-c7ee48ed53f9 |  error | 1970-01-01T00:00:00.000000000Z |
```

### Delete Job

To delete a job entirely and have the system forget the job details, the `--delete-job` flag can be used.

``` shell
bin/run.sh --delete-job myjob
```

For a more comprehensive reference of all LRSPipe options, see the [Options](options.md) page.

[<- Back to README](../README.md)

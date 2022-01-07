[<- Back to Index](index.md)
# LRSPipe JSON-based Job Config

Instead of defining all job configuration in [CLI arguments](options.md) it is also possible to put all of your job config into a JSON file and launch jobs from it. In this section we will go over the types of configuration possible with a JSON file and the structure of the file.

## JSON Options

To run a job with a JSON config file simply use:

`bin/run.sh --json-file [location of JSON config file]`

Alternatively if you would like to simply provide the JSON config inline as a string, you can use the following:

`bin/run.sh --json [full JSON config string]`

Below is an example of all of the JSON fields that can be used in a config file. We will break these down in sections below.

```json
{
  "id": "NewJobID",
  "version": 0,
  "config": {
    "get-buffer-size": 10,
    "statement-buffer-size": 500,
    "batch-buffer-size": 10,
    "batch-timeout": 200,
    "cleanup-buffer-size": 50,
    "source": {
      "request-config": {
        "url-base": "http://localhost:8080",
        "xapi-prefix": "/xapi",
        "username": "username",
        "password": "password"
      },
      "get-params": {
        "limit": 50
      },
      "poll-interval": 1000,
      "batch-size": 50,
      "backoff-opts": {
        "budget": 10000,
        "max-attempt": 10
      }
    },
    "target": {
      "request-config": {
        "url-base": "http://localhost:9080",
        "xapi-prefix": "/xapi",
        "username": "username",
        "password": "password"
      },
      "batch-size": 50,
      "backoff-opts": {
        "budget": 10000,
        "max-attempt": 10
      }
    },
    "filter": {
      "pattern": {
        "profile-urls": [
          "https://xapinet.org/xapi/example-profile/v1"
        ],
        "pattern-ids": [
          "https://xapinet.org/xapi/example-profile/v1/patterns#pattern-1"
        ]
      }
    }
  },
  "state": {
    "status": "init",
    "cursor": "1970-01-01T00:00:00.000000000Z",
    "source": {
      "errors": []
    },
    "target": {
      "errors": []
    },
    "errors": [],
    "filter": {}
  }
}
```
### ID and Top-level Config Options
```json
{
  "id": "NewJobID",
  "version": 0,
  "config": {
    "get-buffer-size": 10,
    "statement-buffer-size": 500,
    "batch-buffer-size": 10,
    "batch-timeout": 200,
    "cleanup-buffer-size": 50,
    ...
  }
  ...
}
```
The `id` field can be specified in much the same way as with `--job-id` on the CLI. This will specify an ID for a new job, or allow you to resume a previously stored job.

The optional `version` field denotes the version of the job specification itself. If not present it will be assumed to be the latest version. JSON jobs specifying older versions will be upgraded if possible or an error will be returned.

The other options under the `config` map are optional and will default to the values specific on the [options guide](options.md). When restarting an existing job these values can be changed via giving them values here, or they will retain the previous job definition's values.

### Source and Target

```json
{
  "config": {
    "source": {
      "request-config": {
        "url-base": "http://localhost:8080",
        "xapi-prefix": "/xapi",
        "username": "username",
        "password": "password"
      },
      "get-params": {
        "limit": 50
      },
      "poll-interval": 1000,
      "batch-size": 50,
      "backoff-opts": {
        "budget": 10000,
        "max-attempt": 10
      }
    },
    "target": {
      "request-config": {
        "url-base": "http://localhost:9080",
        "xapi-prefix": "/xapi",
        "username": "username",
        "password": "password"
      },
      "batch-size": 50,
      "backoff-opts": {
        "budget": 10000,
        "max-attempt": 10
      }
    },
    ...
  }
  ...
}
```
Source and Target are responsible for providing information about the respective source and target LRS configurations. All of the options correspond to the `--source-*` and `--target-*` arguments in the CLI reference. The only difference is you have to set `xapi-prefix` as a separate field. Any option with a default can be omitted from the JSON config and it will take the default value or the previously set value if resuming a stored job.

These sections can be used to update LRS config for paused jobs, for instance in the case where LRS credentials are changed.

### Filter

```json
{
  "config": {
    "filter": {
      "pattern": {
        "profile-urls": [
          "https://xapinet.org/xapi/example-profile/v1"
        ],
        "pattern-ids": [
          "https://xapinet.org/xapi/example-profile/v1/patterns#pattern-1"
        ]
      }
    },
    ...
  }
  ...
}
```
The filter map contains all of the filtering options passed to LRS Pipe. Much like the other sections these fields correspond to the many filter CLI args found on the [options page](options.md). We will cover a few basic scenarios here.

#### Pattern Filtering

In the snippet in the above section you can see how an xAPI Profile and a specific Pattern ID might be provided, analogous to the use of `--pattern-profile-url` and `--pattern-id` args.

#### Template Filtering

```json
{
  "config": {
    "filter": {
      "template": {
        "profile-urls": [
          "https://xapinet.org/xapi/example-profile/v1"
        ],
        "template-ids": [
          "https://xapinet.org/xapi/example-profile/v1/templates#template-1"
        ]
      }
    },
    ...
  }
  ...
}
```
Above you can see how you would apply the same structure but for a template-based filter.

#### Concept Filtering

```json
{
  "config": {
    "filter": {
      "concept": {
        "profile-urls": [
          "https://xapinet.org/xapi/example-profile/v1"
        ],
        "concept-types": [
          "Verb"
        ],
        "activity-type-ids": [],
        "verb-ids": [
          "https://xapinet.org/xapi/example-profile/v1/concepts#verb-1"
        ],
        "attachment-usage-types": []
      }
    },
    ...
  },
  ...
}
```

Above you can see how you can choose a Profile, what types of concepts to filter, and specific IDs to filter for.

#### JsonPath Filtering

```json
{
  "config": {
    "filter": {
      "path": {
        "ensure-paths": [
          [["result"],["score"],["scaled"]]
        ],
        "match-paths": [
          [[["verb"],["id"]],"http://example.com/verb"]
        ]
      }
    }
  }
}
```
The above example shows both an `ensure-path` (`$.result.score.scaled`) and `match-path` (`$.verb.id=http://example.com/verb`) filter. Note that in the JSON representation these are not stored as JSONPath syntax and are rather an internal format LRSPipe uses. If this is unintuitive, you can generate the appropriate syntax from JSONPath using the instructions below for Generating JSON Config.

### State

```json
{
  "state": {
    "status": "init",
    "cursor": "1970-01-01T00:00:00.000000000Z",
    "source": {
      "errors": []
    },
    "target": {
      "errors": []
    },
    "errors": [],
    "filter": {}
  }
}
```
Manipulating state in JSON config can become exceptionally complex and dangerous as this is the raw data representation of the running state of a job and out of scope of usage documentation. It is recommended that you simply preserve this section as-is. For resuming jobs this section will be entirely ignored in favor of the stored state. For that reason we will only cover one possibly relevant field here.

- `cursor`: This field tracks the progress of where to load statements (by `stored` time) from the source LRS. By modifying it you can limit the synchronized statements by stored time. *NOTE: This cannot be changed for an existing stored job. Probably the safer way of doing this is to add the `since` key to `get-params` to make the source query start later than epoch.*

## Generating JSON Config

If you would like to convert CLI job args into a config file, for debugging purposes or to help build a working saved config, there is an option to do this. When running LRSPipe from CLI simply add

`--json-out [desired file location]`

Rather than run the job, LRSPipe will write the JSON equivalent configuration to the filename provided.

[<- Back to Index](index.md)

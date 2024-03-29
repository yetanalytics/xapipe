![SQL LRS Logo](doc/img/logo.png)

# Yet Analytics LRSPipe

[![CI](https://github.com/yetanalytics/xapipe/actions/workflows/ci.yml/badge.svg)](https://github.com/yetanalytics/xapipe/actions/workflows/ci.yml)
[![Docker Image Version (tag latest semver)](https://img.shields.io/docker/v/yetanalytics/xapipe/latest?color=blue&label=docker&style=plastic)](https://hub.docker.com/r/yetanalytics/xapipe)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-5e0b73.svg)](CODE_OF_CONDUCT.md)

LRSPipe enables the Total Learning Architecture by acting as middleware between layers of data and by governing data flow directly based on xAPI Profiles. It’s more than an xAPI statement forwarder — it’s a forwarder that is governed by xAPI Profiles.

## How it Works

LRSPipe is a standalone process which runs independently of any LRS. This process can run one or multiple "jobs" at a time, each of which contains information about a source LRS, a target LRS, and any applicable filters or options. While running, this process continually checks the source LRS(s) for xAPI Statements and attempts to replicate them into the target LRS(s).

This process is one-way and any statements in the target LRS will not be replicated to the source LRS. These jobs can be paused, resumed, and modified, and LRSPipe tracks its progress using storage (either the local file system or a Redis server, depending on how it is configured) so a job can be interrupted at any time and pick right back up where it left off when it resumes.

### Filtering

LRSPipe is capable of filtering which statements get forwarded. This filtering is performed using the components of provided xAPI Profiles. For more information see the [xAPI Profile Specification](https://github.com/adlnet/xapi-profiles).

#### Full Forwarding

In this mode all statements from the source LRS will be replicated into the target LRS.

#### Statement Template Filtering

In this mode LRSPipe is provided with the location of an xAPI Profile and will only forward statements that match the Statement Templates in the Profile.

This can be made to be even more specific by providing a set of Statement Template Ids, which will cause it to only forward a subset of Statement Templates from a Profile. See [usage](doc/usage.md) for details and examples.

#### Pattern Filtering

In this mode LRSPipe is provided with the location of an xAPI Profile and will attempt to match statements to these Patterns, and will validate and forward matching statements.

Much like Statement Template Filtering, this can also be limited to a set of Pattern Ids if one is provided. See [usage](doc/usage.md) for details and examples.

## Releases

For releases and release notes, see the [Releases](https://github.com/yetanalytics/xapipe/releases/latest) page.

## Documentation

- [Installation](doc/install.md)
- [Usage](doc/usage.md)
- [Persistence Config](doc/persistence.md)
- [All Options](doc/options.md)
- [JSON Config](doc/json.md)
- [Metrics](doc/metrics.md)
- [Docker Container](doc/docker.md)
- [Demo](doc/demo.md)
- [Sample AWS Deployment](doc/aws.md)

## Contribution

Before contributing to this project, please read the [Contribution Guidelines](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Copyright © 2021-2024 Yet Analytics, Inc.

Distributed under the Apache License version 2.0.

[<- Back to README](../README.md)
# LRSPipe Persistence Configuration

LRSPipe uses storage to save job status and progress. This enables resuming paused jobs and finding out the status of a job. In this section we will look at a few configurable options for how LRSPipe stores it's state.

## File System (Default)

Be default, LRSPipe uses the local filesystem for storage. It stores job data in individual files in a directory. Without specifying any options it will store these files in the directory `/store` at the root of where you run the job (presumably your unzipped LRSPipe release directory). You can change that location with the following argument:

``` shell
bin/run.sh ... \
           --file-store-dir "../desired-storage-dir" \
           ...
```

The directory location specified will be the storage location for job files.

_NOTE: If you have existing jobs and you change locations between runs LRSPipe will no longer be able to find them._

## Redis

If you wish to instead store job details in a Redis server you can do so by specifying the Redis server connection URI below:

``` shell
bin/run.sh ... \
           --storage redis
           --redis-uri URI \
           ...
```
The URI should be in Redis format, which includes the following possible formats:

```
redis://HOST[:PORT][?db=DATABASE[&password=PASSWORD]]
redis://HOST[:PORT][?password=PASSWORD[&db=DATABASE]]
redis://[:PASSWORD@]HOST[:PORT][/DATABASE]
redis://[:PASSWORD@]HOST[:PORT][?db=DATABASE]
redis://HOST[:PORT]/DATABASE[?password=PASSWORD]
```
If you omit the URI it will default to `redis://0.0.0.0:6379`.

Additionally if you wish to provide a custom string such that all LRSPipe keys contain it as a prefix (e.g. in the case of a shared-use Redis server), you can do so with the `--redis-prefix` flag. If you do not include this argument, all related Redis keys will be prefixed with `xapipe`.

## In-Memory / No-Op

If you do not wish to store job status and progress at all, and would like LRSPipe to completely refresh on every restart, you can do so by specifying `noop` for the `--storage` flag. Keep in mind this will result in you not being able to resume a job at all and you will lose your progress any time the process is interrupted.

[<- Back to README](../README.md)

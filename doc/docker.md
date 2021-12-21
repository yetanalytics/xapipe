[<- Back to Index](index.md)
# LRSPipe Docker Container

For ease of deployment, LRSPipe is also distributed as a Docker container available on [DockerHub](https://hub.docker.com/r/yetanalytics/xapipe).

The same [options](options.md) as used in the CLI are available as arguments to the container's run command.

## Invocation

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

[<- Back to Index](index.md)

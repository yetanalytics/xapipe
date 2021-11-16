[<- Back to README](../README.md)
# LRSPipe Docker Container

For

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

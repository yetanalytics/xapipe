[<- Back to README](../README.md)

# Metrics

The xapipe CLI supports prometheus metrics via a [push gateway](https://github.com/prometheus/pushgateway). With a push gateway set up, you can use it like so:

``` shell
bin/run.sh --source-url http://0.0.0.0:8080/xapi \
           --target-url http://0.0.0.0:8081/xapi \
           --metrics-reporter prometheus \
           --prometheus-push-gateway 0.0.0.0:9091
```

The following Prometheus metrics are implemented:

## Counters

* `xapipe_statements`
* `xapipe_attachments`
* `xapipe_job_errors`
* `xapipe_source_errors`
* `xapipe_target_errors`
* `xapipe_all_errors`

## Gauges

* `xapipe_source_request_time`
* `xapipe_target_request_time`

[<- Back to README](../README.md)

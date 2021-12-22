[<- Back to Index](index.md)

# Example Forwarding Demo

This repo includes a Docker Compose file at [`demo/docker-compose.yml`](https://github.com/yetanalytics/xapipe/blob/main/demo/docker-compose.yml) that creates source and target LRS instances using [SQL LRS](https://github.com/yetanalytics/lrsql) and uses LRSPipe to forward data between them. This demo only requires having Docker 3.9+ installed.

To run the demo:

``` shell
cd demo
docker compose up
```

This will create a source LRS at `http://0.0.0.0:8080` and a target LRS at `http://0.0.0.0:8081`. The credentials for each can be found (and changed) in the `docker-compose.yml` file. If you send xAPI data to the source it will be forwarded to the target.

The demo includes a [Prometheus](https://prometheus.io/) metrics server and push gateway. When the demo is running you can navigate to [http://0.0.0.0:9090](http://0.0.0.0:9090) and explore xapipe metrics (see below).

In addition to prometheus the demo creates a [Grafana](https://github.com/grafana/grafana) server at [http://0.0.0.0:3000](http://0.0.0.0:3000). Log in with username `admin` and password `admin` and set a password, then you can view a comprehensive dashboard with all metrics. See [metrics](metrics.md) for more details.

[<- Back to Index](index.md)

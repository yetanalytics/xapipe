[<- Back to README](../README.md)
# LRSPipe Installation

## From Release Distribution

LRSPipe can be downloaded as cross-platform standalone application. You can download xapipe from our [release page](https://github.com/yetanalytics/xapipe/releases/latest) or on the command line:

``` shell
curl -L https://github.com/yetanalytics/xapipe/releases/latest/download/xapipe.zip -o xapipe.zip
unzip xapipe.zip -d xapipe
cd xapipe
bin/run.sh --help
```

## Build from Source

If you would like to build LRSPipe from the source code in this repository, you will need Java JDK 11+ and Clojure 1.10+. Clone the repository and from the root directory run the following:

``` shell
make bundle
cd target/bundle
bin/run.sh --help
```

For basic usage instructions please see the [usage](usage.md) page.

[<- Back to README](../README.md)

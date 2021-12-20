[<- Back to Index](index.md)
# LRSPipe JSON-based Job Config

Instead of defining all job configuration in [CLI arguments](options.md) it is also possible to put all of your job config into a JSON file and launch jobs from it. In this section we will go over the types of configuration possible with a JSON file and the structure of the file.

## JSON Options




```
{:get-buffer-size 100,
          :statement-buffer-size 1000,
          :batch-buffer-size 100,
          :batch-timeout 200,
          :cleanup-buffer-size 50,
          :source
          {:request-config
           {:url-base "http://0.0.0.0:8080",
            :xapi-prefix "/xapi",
            :username "foo",
            :password "bar"},
           :get-params {:limit 50},
           :poll-interval 1000,
           :batch-size 50,
           :backoff-opts
           {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
          :target
          {:request-config
           {:url-base "http://0.0.0.0:8081",
            :xapi-prefix "/xapi",
            :username "foo",
            :password "bar"},
           :batch-size 50,
           :backoff-opts
           {:budget 1000, :max-attempt 10, :j-range 10, :initial 1}},
          :filter {}}
```




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

[<- Back to Index](index.md)

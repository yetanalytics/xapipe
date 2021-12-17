.phony: test bench clean bundle bundle-help

clean:
	rm -rf target dev-resources/bench/*.json

JAVA_MODULES ?= $(shell cat .java_modules)

test:
	clojure -J--limit-modules -J$(JAVA_MODULES) -X:cli:test :dirs '["src/test"]'

BENCH_SIZE ?= 10000
BENCH_PROFILE ?= dev-resources/profiles/calibration.jsonld

dev-resources/bench/payload.json:
	clojure -Xtest:bench write-payload \
		:num-statements $(BENCH_SIZE) \
		:profile '"$(BENCH_PROFILE)"' \
		:out '"dev-resources/bench/payload.json"'

bench: dev-resources/bench/payload.json
	clojure -Xtest:bench run-bench-matrix \
		:num-statements $(BENCH_SIZE) \
		:payload-path '"dev-resources/bench/payload.json"'

target/bundle/xapipe.jar:
	clojure -T:build uber

target/bundle/bin:
	mkdir -p target/bundle/bin
	cp bin/*.sh target/bundle/bin
	chmod +x target/bundle/bin/*.sh

# publish docs

target/bundle/doc:
	clojure -X:doc

# Make Runtime Environment (i.e. JREs)
# Will only produce a single jre for macos/linux matching your machine
MACHINE ?= $(shell bin/machine.sh)

target/bundle/runtimes:
	mkdir -p target/bundle/runtimes
	jlink --output target/bundle/runtimes/${MACHINE}/ --add-modules ${JAVA_MODULES}

BUNDLE_RUNTIMES ?= true

ifeq ($(BUNDLE_RUNTIMES),true)
target/bundle: target/bundle/xapipe.jar target/bundle/bin target/bundle/doc target/bundle/runtimes
else
target/bundle: target/bundle/xapipe.jar target/bundle/bin target/bundle/doc
endif

bundle: target/bundle

# Run the bundle's help, used for compile-time sanity checks
bundle-help: target/bundle
	cd target/bundle; bin/run.sh --help

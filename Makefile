.phony: test-lib bench clean bundle bundle-help

clean:
	rm -rf target

test-lib:
	clojure -X:cli:test :dirs '["src/test"]'

bench:
	clojure -Xtest:bench

target/bundle/xapipe.jar:
	clojure -T:build uber

target/bundle/bin:
	mkdir -p target/bundle/bin
	cp bin/*.sh target/bundle/bin
	chmod +x target/bundle/bin/*.sh

# Make Runtime Environment (i.e. JREs)
# Will only produce a single jre for macos/linux matching your machine
MACHINE ?= $(shell bin/machine.sh)
JAVA_MODULES ?= $(shell cat .java_modules)

target/bundle/runtimes:
	mkdir -p target/bundle/runtimes
	jlink --output target/bundle/runtimes/${MACHINE}/ --add-modules ${JAVA_MODULES}

BUNDLE_RUNTIMES ?= true

ifeq ($(BUNDLE_RUNTIMES),true)
target/bundle: target/bundle/xapipe.jar target/bundle/bin target/bundle/runtimes
else
target/bundle: target/bundle/xapipe.jar target/bundle/bin
endif

bundle: target/bundle

# Run the bundle's help, used for compile-time sanity checks
bundle-help: target/bundle
	cd target/bundle; bin/run.sh --help

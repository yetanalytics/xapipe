.phony: test-lib bench clean bundle

clean:
	rm -rf target classes

test-lib:
	clojure -X:cli:test :dirs '["src/test"]'

bench:
	clojure -Xtest:bench

target/bundle/xapipe.jar:
	mkdir -p target/bundle
	clojure -Xuberjar

target/bundle/bin:
	mkdir -p target/bundle/bin
	cp bin/*.sh target/bundle/bin
	chmod +x target/bundle/bin/*.sh

# Make Runtime Environment (i.e. JREs)

# The given tag to pull down from yetanalytics/runtimer release
RUNTIME_TAG ?= 0.1.1-java-11-temurin
RUNTIME_MACHINE ?= macos
RUNTIME_MACHINE_BUILD ?= macos-10.15
RUNTIME_ZIP_DIR ?= tmp/runtimes/${RUNTIME_TAG}
RUNTIME_ZIP ?= ${RUNTIME_ZIP_DIR}/${RUNTIME_MACHINE}.zip
JAVA_MODULES ?= $(shell cat .java_modules)

# DEBUG: Kept here for reference
# target/bundle/runtimes: target/bundle/bin
# 	mkdir target/bundle/runtimes
# 	jlink --output target/bundle/runtimes/$(MACHINE_TYPE) --add-modules $(JAVA_MODULES)

target/bundle/runtimes/%:
	mkdir -p ${RUNTIME_ZIP_DIR}
	mkdir -p target/bundle/runtimes
	[ ! -f ${RUNTIME_ZIP} ] && curl -L -o ${RUNTIME_ZIP} https://github.com/yetanalytics/runtimer/releases/download/${RUNTIME_TAG}/${RUNTIME_MACHINE_BUILD}-jre.zip || echo 'already present'
	unzip ${RUNTIME_ZIP} -d target/bundle/runtimes/
	mv target/bundle/runtimes/${RUNTIME_MACHINE_BUILD} target/bundle/runtimes/${RUNTIME_MACHINE}

target/bundle/runtimes/macos: RUNTIME_MACHINE = macos
target/bundle/runtimes/macos: RUNTIME_MACHINE_BUILD = macos-10.15

target/bundle/runtimes/linux: RUNTIME_MACHINE = linux
target/bundle/runtimes/linux: RUNTIME_MACHINE_BUILD = ubuntu-20.04

target/bundle/runtimes/windows: RUNTIME_MACHINE = windows
target/bundle/runtimes/windows: RUNTIME_MACHINE_BUILD = windows-2019

target/bundle/runtimes: target/bundle/runtimes/macos target/bundle/runtimes/linux target/bundle/runtimes/windows

BUNDLE_RUNTIMES ?= true

ifeq ($(BUNDLE_RUNTIMES),true)
target/bundle: target/bundle/xapipe.jar target/bundle/bin target/bundle/runtimes
else
target/bundle: target/bundle/xapipe.jar target/bundle/bin
endif

bundle: target/bundle

.phony: test-lib bench clean bundle

clean:
	rm -rf target classes

test-lib:
	clojure -X:cli:test :dirs '["src/test"]'

bench:
	clojure -Xtest:bench

target/xapipe.jar:
	mkdir -p target
	clojure -Xuberjar :jar "target/xapipe.jar"

# a la https://www.redpill-linpro.com/techblog/2021/03/31/faster-clojure-with-graalvm.html
# target/native: target/xapipe.jar
# 	mkdir -p target/native
# 	native-image -cp target/xapipe.jar -jar target/xapipe.jar \
# 		-H:Name=xapipe -H:+ReportExceptionStackTraces \
# 		-J-Dclojure.spec.skip.macros=true -J-Dclojure.compiler.direct-linking=true -J-Xmx3G \
# 		--initialize-at-run-time=org.apache.http.impl.auth.NTLMEngineImpl \
# 		--initialize-at-build-time --enable-http --enable-https --verbose --no-fallback --no-server\
# 		--report-unsupported-elements-at-runtime --native-image-info \
# 		-H:+StaticExecutableWithDynamicLibC -H:CCompilerOption=-pipe \
# 		--allow-incomplete-classpath --enable-url-protocols=http,https --enable-all-security-services
# 	mv xapipe target/native/

target/native: target/xapipe.jar
	mkdir -p target/native
	native-image \
	-H:+ReportExceptionStackTraces \
	--no-fallback \
	-jar target/xapipe.jar \
	 target/native/xapipe


# Distribution Bundle

target/bundle/xapipe.jar:
	mkdir -p target/bundle
	clojure -Xuberjar

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

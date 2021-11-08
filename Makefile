.phony: test-lib bench clean bundle

clean:
	rm -rf target classes

test-lib:
	clojure -X:cli:test :dirs '["src/test"]'

bench:
	clojure -Xtest:bench

target/xapipe.jar:
	mkdir target
	clojure -Xuberjar

target/bundle/bin/run.sh:
	mkdir -p target/bundle/bin
	echo "#!/bin/sh\n" > target/bundle/bin/run.sh
	echo 'java -server -jar xapipe.jar $$@\n' >> target/bundle/bin/run.sh
	chmod +x target/bundle/bin/run.sh

# For docker
target/bundle/bin/entrypoint.sh:
	mkdir -p target/bundle/bin
	echo "#!/bin/sh\n" > target/bundle/bin/entrypoint.sh
	echo 'runtimes/linux/bin/java -server -jar xapipe.jar $$@\n' >> target/bundle/bin/entrypoint.sh
	chmod +x target/bundle/bin/entrypoint.sh

target/bundle: target/xapipe.jar target/bundle/bin/run.sh target/bundle/bin/entrypoint.sh
	mkdir -p target/bundle/bin
	cp target/xapipe.jar target/bundle/xapipe.jar

bundle: target/bundle

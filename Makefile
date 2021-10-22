.phony: test-lib

test-lib:
	clojure -X:test :dirs '["src/test"]'

.phony: test-lib

test-lib:
	clojure -X:cli:test :dirs '["src/test"]'

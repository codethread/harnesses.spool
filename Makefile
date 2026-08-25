.PHONY: test format format-fix lint lint-splint check

test:
	clojure -M:test

format:
	clojure -M:format

format-fix:
	clojure -M:format/fix

lint:
	mkdir -p .clj-kondo
	clojure -M:lint/clj-kondo --lint "$$(clojure -Spath -M:lint/clj-kondo)" --dependencies --parallel --copy-configs --skip-lint
	clojure -M:lint/clj-kondo --lint src test

lint-splint:
	clojure -M:lint/splint

check: format lint lint-splint test

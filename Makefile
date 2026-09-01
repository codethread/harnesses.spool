CLJ_KONDO := clj-kondo
CLJ_KONDO_VERSION := 2026.08.04

.PHONY: test format format-fix lint lint-splint check check-clj-kondo

test:
	clojure -M:test

format:
	clojure -M:format

format-fix:
	clojure -M:format/fix

lint: check-clj-kondo
	mkdir -p .clj-kondo
	$(CLJ_KONDO) --repro --lint "$$(clojure -Spath -M:test)" --dependencies --parallel --copy-configs --skip-lint
	$(CLJ_KONDO) --repro --parallel --lint src test

check-clj-kondo:
	@command -v $(CLJ_KONDO) >/dev/null 2>&1 || { \
		echo "clj-kondo $(CLJ_KONDO_VERSION) is required" >&2; \
		exit 1; \
	}
	@actual="$$($(CLJ_KONDO) --version)"; \
	expected="clj-kondo v$(CLJ_KONDO_VERSION)"; \
	if [ "$$actual" != "$$expected" ]; then \
		echo "Expected $$expected, found $$actual" >&2; \
		exit 1; \
	fi

lint-splint:
	clojure -M:lint/splint

check: format lint lint-splint test

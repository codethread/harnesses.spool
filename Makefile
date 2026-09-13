CLJ := clojure
CLJ_KONDO := clj-kondo
CLJ_KONDO_VERSION := 2026.08.04

.PHONY: test format format-fix lint lint-splint check check-clj-kondo \
	kondo kondo-import kondo-import-root kondo-import-workspace \
	kondo-lint kondo-lint-root kondo-lint-workspace lsp-diagnostics

test:
	clojure -M:test

format:
	clojure -M:format

format-fix:
	clojure -M:format/fix

lint: kondo

kondo: kondo-import
	$(MAKE) kondo-lint

kondo-import: check-clj-kondo
	$(MAKE) kondo-import-root
	$(MAKE) kondo-import-workspace

kondo-import-root:
	mkdir -p .clj-kondo
	rm -rf .clj-kondo/imports
	set -e; classpath="$$($(CLJ) -Srepro -Spath -M:test)"; \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-import-workspace:
	mkdir -p .millstrand/.clj-kondo
	rm -rf .millstrand/.clj-kondo/imports
	cd .millstrand && set -e && classpath="$$($(CLJ) -Srepro -Spath)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-lint:
	$(MAKE) kondo-lint-root
	$(MAKE) kondo-lint-workspace

kondo-lint-root:
	$(CLJ_KONDO) --repro --parallel --lint src test

kondo-lint-workspace:
	cd .millstrand && $(CLJ_KONDO) --repro --parallel --lint init.clj

lsp-diagnostics:
	@set -e; xdg="$$(mktemp -d)"; cache="$$(mktemp -d)"; \
		trap 'rm -rf "$${xdg:?}" "$${cache:?}"' EXIT; \
		XDG_CONFIG_HOME="$$xdg" clojure-lsp diagnostics --raw --project-root . \
			--settings "{:cache-path \"$$cache\" :project-specs [{:project-path \"deps.edn\" :classpath-cmd [\"clojure\" \"-Srepro\" \"-Spath\" \"-M:test\"]}]}" \
			--filenames src,test; \
		XDG_CONFIG_HOME="$$xdg" clojure-lsp diagnostics --raw --project-root .millstrand \
			--settings "{:cache-path \"$$cache\" :project-specs [{:project-path \"deps.edn\" :classpath-cmd [\"clojure\" \"-Srepro\" \"-Spath\"]}]}" \
			--filenames init.clj

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

check: format kondo lint-splint test

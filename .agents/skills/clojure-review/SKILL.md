---
name: clojure-review
description: always use for reviewing clojure code
---

# Clojure Review Guide

Always load the base `clojure` skill.

When auditing existing Clojure, evaluate it against this desired style even if the file predates the current change. Report concrete deviations; do not summarize architecture or positives.

## Docstrings

Docstrings are expected for public API vars more often than specs are.

Use docstrings for:

- Every public `defn`, `defmacro`, protocol, record/type, and important public `def`.
- Namespaces with non-trivial purpose.
- Private functions only when intent, invariants, or domain behavior is not obvious.

For audits, treat a top-level var as public unless it has `^:private`, is defined with `defn-`, or the namespace clearly marks it as internal. Missing docstrings on public vars are reportable conformity issues, especially for API, DB, query, REPL, and namespace entry-point functions.

Example:

```clojure
(defn ready
  "Return open strands whose blocking dependencies are closed.

  Traverse only declared structural dependency relations.

  Annotation edges do not affect readiness."
  [db]
  ...)
```

## Audit red flags

When scanning Clojure conformity, actively look for and report these before listing positives:

- Any source line past column 180; prose whose own block runs well past 80 characters; or multi-paragraph prose built from `(str ...)` fragments instead of `millstrand.api.format.alpha/prose`.
- Public namespace lacks an `ns` docstring and has a non-trivial public role.
- Public `defn`, `defmacro`, protocol, record/type, or important public `def` lacks a useful docstring.
- Boundary data is validated only ad hoc when a reusable spec would clarify the shipped contract or enable generated checks.
- Pure invariant-heavy behavior lacks property/spec tests where generators would be meaningful.
- Invalid input silently becomes a no-op, default value, SQL three-valued-logic surprise, coercion, or misleading empty result instead of failing loudly.
- Public functions or `def` values appear unused, under-tested, or accidentally public. Constants, SQL fragments, dynamic compiler state, and implementation tables should normally be `^:private` unless they are intentional API and documented.
- Tests only cover examples when a broad invariant would be better captured by property testing.

## Auditing Clojure conformity

1. List public top-level vars in the audited files; flag missing or unhelpful docstrings for public API vars.
2. Classify each public var as intentional API or accidental exposure; flag implementation constants, SQL strings, state vars, helper tables, and internal utilities that should be `^:private`.
3. Identify boundary data shapes and pure invariant-heavy functions; flag missing specs/property tests only when they would provide real contract or generated-test value.
4. Check fail-loud behavior: explicit invalid input should throw useful `ex-info` or equivalent, not silently no-op, coerce, default, or produce misleading results.
5. Check tests: broad invariants should have property/spec coverage when practical; concrete regressions and I/O workflows should have example tests.
6. Check style: namespace docstring, requires, naming, side-effect boundaries, dense forms, dead public functions, and linter-obvious issues.
7. Report findings as deviations with severity when useful. Avoid long positive inventories unless no deviations exist.

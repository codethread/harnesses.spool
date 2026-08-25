---
name: clojure
description: always use for authoring Clojure code, including docstrings, comments, and prose values; also use for reviewing, auditing, or scanning Clojure code conformity
---

# Clojure Authoring Guide

## Knowledge

### Project posture

- Prefer small, data-oriented APIs and pure transformation functions.
- Keep side effects at system boundaries: DB, files, sockets, subprocesses, REPL/runtime state.
- Fail loudly on invalid input. Do not silently coerce, default, or tolerate malformed data unless the contract explicitly says so.
- Public behavior should be discoverable from namespace organization, docstrings, specs where useful, and tests.
- When writing existing Clojure, evaluate it against this desired style even if the file predates the current work. Fix poor code as you go.

### Idiomatic specs

Use `clojure.spec.alpha` as a selective contract and generative-testing tool, not as mandatory ceremony for every non-trivial function.

Prefer specs for:

- Data shapes at boundaries: config maps, DB rows/entities, API payloads, wire JSON/EDN shapes, query DSL forms, batch operations.
- Public API functions where `s/fdef` clarifies a contract or enables useful generative tests.
- Pure transformations with meaningful invariants: graph operations, normalization, parsers/printers, encoders/decoders, round-trips, idempotent updates, topological/DAG operations.

Usually skip specs for:

- Thin wrappers and delegation functions.
- Local/private helpers whose contract is obvious from their caller.
- I/O orchestration where example/integration tests communicate behavior better.
- Functions where generators would be hard to make realistic or would only produce trivial cases.

A good default rule:

> Spec data contracts broadly; spec function contracts selectively where generated inputs or API documentation will catch real bugs.

Runtime validation and function specs have different jobs:

- At untrusted, dynamic, persistence, process, and extension boundaries, use
  explicit runtime validation such as `require-valid!`. Boundary validation must
  not depend on optional spec instrumentation being enabled.
- Use `s/fdef` when a concrete consumer justifies it: useful instrumentation,
  generative tests with realistic generators, an input/output relation, or API
  tooling that reads function specs.
- Do not add `s/fdef` merely because a function is public or already performs
  runtime validation. That duplicates the contract and creates drift without
  adding enforcement.
- Pure transformations with meaningful properties are often better `s/fdef`
  candidates than stateful public orchestration. They may live in an internal
  namespace when they are not a supported public API.
- `clojure.spec.test.alpha/instrument` is an opt-in development and test aid. It
  checks arguments on calls through instrumented Vars; it is not production
  validation and does not replace explicit return validation.

When an `s/fdef` is justified, place it immediately after the function it
specifies. This keeps the callable contract attached to the implementation
instead of collecting function specs in a detached block at the bottom of the
namespace. Define prerequisite data specs before the specified functions.

### Property-based testing with spec

`clojure.spec.test.alpha/check` is most valuable when the `:fn` relation states a property between inputs and outputs, not merely when `:args` and `:ret` check shape.

Good property candidates:

- Encode/decode and parse/print round-trips preserve data.
- Normalization is idempotent.
- Upsert/merge is idempotent or monotonic as intended.
- Adding graph edges preserves declared acyclic relation invariants.
- Query evaluation agrees with simpler reference predicates.
- Sorting/traversal returns only valid members and respects dependency ordering.

Do not add property tests with weak generators just to claim coverage. A few concrete regression examples may be better than a shallow generator.

### Docstrings, comments, and prose values

Good prose in Clojure source is formatted for the person reading the source, not merely kept below a lint limit. Aim for about 80 characters of prose within its own block, independent of how far the enclosing form is indented. Column 180 remains the hard limit for every docstring and string-literal line.

#### Docstrings

Docstrings are expected for public API vars more often than specs are.

Use docstrings for:

- Every public `defn`, `defmacro`, protocol, record/type, and important public `def`.
- Namespaces with non-trivial purpose.
- Private functions only when intent, invariants, or domain behavior is not obvious.

For audits, treat a top-level var as public unless it has `^:private`, is defined with `defn-`, or the namespace clearly marks it as internal. Missing docstrings on public vars are reportable conformity issues, especially for API, DB, query, REPL, and namespace entry-point functions.

Start with a concise standalone summary, preferably imperative: `Return ...`, `Create ...`, `Evaluate ...`. Add a blank line before details. Explain contracts, side effects, important invariants, and return shape when they are not obvious. Do not restate the function name or narrate implementation mechanics.

A docstring is long-form prose, not one dense paragraph wrapped wherever the width limit happens to fall:

- Give each distinct idea, phase, caution, or consequence its own paragraph.
- Put commands and examples in fenced code blocks instead of burying them inline.
- Use a list when the reader must scan several requirements, cases, or guarantees.
- Prefer comfortable reading rhythm over terseness. Blank lines are useful structure.
- Wrap by the width of the docstring body, not by the source file's absolute column.

```clojure
(defn ready
  "Return open strands whose blocking dependencies are closed.

  Traverse only declared structural dependency relations.

  Annotation edges do not affect readiness."
  [db]
  ...)
```

Docstrings are macro syntax and metadata as well as strings. Ordinary Clojure docstring positions require a literal, so wrap them by hand. A project macro may explicitly accept a computed documentation expression; use a runtime formatting helper only when that macro's contract permits it.

#### Long-form comments

Use comments to explain why the code has a shape, which invariant it protects, or which non-obvious trade-off it accepts. Do not translate the next form into English or preserve history that belongs in version control.

Format a long comment like prose:

- Keep each paragraph to one concern.
- Separate paragraphs with a blank `;;` line.
- Wrap the comment text itself at about 80 characters, regardless of indentation.
- Use a short list or example when it is easier to scan than a sentence.

```clojure
;; The cache belongs to one runtime generation. Refresh may add roots, but it
;; cannot unload classes already visible to this classloader.
;;
;; Keep invalidation generation-scoped. Clearing individual entries would imply
;; that a non-additive source change can take effect in the current generation.
```

#### Prose stored as data

When a string value carries Markdown or multi-paragraph guidance, use `millstrand.api.format.alpha/prose`. It removes the enclosing form's baseline indentation while preserving paragraphs, lists, code fences, and deliberate line breaks.

````clojure
(format-alpha/prose
 "
   Inspect the failed check before changing the source.

   Retry it with:

   ```sh
   tool retry <check-id> --attributes '{patch:json}'
   ```

   Record why the retry is safe.
   "
 {:patch {"error" nil}})
````

Use named interpolation instead of nested string construction or escaped snippets. `{name}` renders an ordinary value and `{name:json}` renders compact JSON. Put a one-use interpolation value directly in the adjacent scope map so the template and the data needed to read it stay together. Hoist a value only when it has genuine meaning or reuse outside that prose call.

Do not turn `prose` blocks into dense walls merely because their authored newlines are preserved. Apply the same paragraph, list, example, and block-relative width rules as docstrings. A useful default is:

1. State the action or fact.
2. Separate supporting context into its own paragraph.
3. Display commands in a fenced block.
4. Put cautions, consequences, and recovery in later paragraphs.

Use `reflow` only for a value whose contract is one plain paragraph with no preserved Markdown layout. Use `fill` only for its established item-vector contract. Do not build long prose from `(str ...)` fragments or one oversized literal.

#### Review prose in source

Before finishing a prose edit:

1. Read the source block at its actual indentation; do not judge it only by rendered output.
2. Check that paragraph breaks follow changes of idea, action, caution, or consequence.
3. Check that commands, examples, and multi-case requirements are easy to scan.
4. Keep authored prose near 80 block-relative characters and every literal line within the absolute 180-column limit.
5. Remove avoidable escaped snippets and detached one-use interpolation values.

### The story-file shape (api modules)

A public namespace should read as a story in CleanCode style: the public fns lead, and each body shows the meat of its algorithm as named, composed steps. The public body surfaces the shape of the problem.

```clojure
;; GOOD: the public fn IS the pipeline; helpers are named steps below it.
(defn link!
  "Link every configured project's dotfiles into place; return the
  projects that changed."
  [config-path]
  (->> (load-configs config-path)
       (pmap project-files-to-link)
       (assert-no-conflicts!)
       (mapv relink-project!)
       (filterv changed?)))
```

```clojure
;; BAD: a delegation husk — the story has been exiled to another file,
;; and the reader learns nothing here.
(defn link!
  "Link every configured project's dotfiles into place."
  [config-path]
  (internal/link! config-path))
```

Concurrency shape is part of the story. Where calls run in sequence, where they fan out, and where the code blocks must read in the public body — a helper that hides a `future`, `pmap`, or blocking deref hides exactly the thing a review should question ("could we have started that fetch sooner?").

```clojure
;; BAD - the fan-out and the blocking joins are buried in the helper.
(defn load-dashboard
  "Assemble the dashboard for `ctx`."
  [ctx]
  (fetch-everything ctx))

;; GOOD - sequence, parallelism, and joins read at the top level.
(defn load-dashboard
  "Assemble the dashboard for `ctx`."
  [ctx]
  (let [profile (future (fetch-profile ctx))   ; starts now
        boards  (future (fetch-boards ctx))    ; runs alongside profile
        prefs   (fetch-prefs ctx)]             ; must resolve before the join
    (render-dashboard @profile @boards prefs)))
```

Keep modules under 500 lines, then break out to `internal/<concern>` files. Ensure tests are still against the public surface

### Public vs private helpers

Do not make a public var private just because it has lower-level mechanics. Classify visibility before changing it.

Keep a var public and add a docstring when:

- another namespace calls it directly;
- tests exercise it as a boundary;
- it is useful from REPL or development sessions;
- it represents a smaller valid API than the highest-level wrapper;
- the name appears in docs, specs, examples, or user-facing guidance.

Make a var private when:

- only same-namespace internals call it;
- it exists to support recursion, dynamic binding context, SQL fragments, helper tables, or implementation state;
- callers should always use a higher-level public function;
- no tests, docs, specs, or adjacent namespaces imply direct use.

If uncertain, prefer documenting over privatizing and report the visibility question as a follow-up.

### Style and tooling

Follow common Clojure community style unless local code establishes a stronger pattern:

- Prefer `let`, threading macros, and small named helpers over dense nested forms.
- Prefer maps and plain data over unnecessary records/classes.
- Prefer explicit requires with aliases; avoid broad `:refer :all` outside tests/REPL-oriented namespaces.
- Keep public functions near the top-level flow where practical; keep private helpers close enough to their use to aid reading.

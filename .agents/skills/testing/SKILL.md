---
name: testing
description: Use when running, writing, or gating tests — warm REPL iteration, Done-when, queue acceptance, weaver-world fixtures, and embedded runtimes.
---

# Testing

## Runtime publication

Tests and embedded runtimes start with `:publish? false` and pass the runtime explicitly. Only a real weaver process publishes an ambient runtime (SPEC-004.C8a).

## Workspaces

Ordinary cold/warm suite runs do not take `--workspace`. Use disposable `--workspace` worlds from `mktemp -d` (guard with `${ws:?}`) for weaver-world fixtures, smoke config, and other workspace-backed experiments — never the shared `.millstrand` coordination world. Fixture tiers: `docs/spools/testing.md`.

## When / what

| When | What |
| --- | --- |
| Iterate a slice | `make test-warm NS="…"` — never Done-when |
| Slice Done-when | `clojure -M:test <ns…>` |
| Queue acceptance | `flock -w 3600 /tmp/millstrand-test.lock clojure -M:test`; `make test-go`; `make test-e2e`; `make fmt-check lint reflect-check docs-check` |

Notes:

- `MILLSTRAND_TEST_AWAIT_SCALE` multiplies await budgets on slow hosts (CI sets 3).
- After spool or `millstrand.api.*.alpha` docstring changes: `make api-docs`.
- After validation, `git status --short` must not show generated SQLite or runtime metadata artifacts.

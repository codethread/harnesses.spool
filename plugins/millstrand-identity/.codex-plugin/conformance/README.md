# Codex native startup fixtures

The packaged adapter is pinned to **codex-cli 0.154.0**. Tests use disposable
homes, plugin copies, projects and workspaces; never installed caches, desktop
sessions, credentials or real model services.

## No-model host conformance

```text
pnpm test:codex-hooks
```

Requires Node, Bash, jq, lockf or flock, and the pinned Codex executable.
The suite verifies:

- SessionStart startup/resume/clear/compact and SubagentStart payload schemas;
- canonical project gating before source inspection, locks or Strand calls;
- exact actual session IDs and encoded parent-session/agent child keys;
- minimal managed run-reference forwarding, with no inherited child reference;
- awaited CLI registration and canonical additional developer context;
- bounded failures, crash-safe duplicate protection and interruption cleanup;
- actual Codex plugin discovery, trust and duplicate-injector handling;
- actual fresh/resumed Codex model-bound input through a local HTTP/SSE fixture.
  One enabled injector contributes one identity message. Duplicate injectors
  stop before any model request.

The four vendored host schemas come from OpenAI Codex tag `rust-v0.154.0`,
commit `6b9826e3aa`. SessionStart supplies model but no reasoning effort.
SubagentStart supplies its parent's `session_id` plus `agent_id`; the native
child key is `codex-child:v1:<base64url(parent UTF-8)>:<base64url(agent UTF-8)>`.

The hook invokes `agent native-startup codex ACTUAL_ID --model MODEL`, with a
three-second request deadline, routed explicitly to the canonical project
workspace. Managed roots additionally pass `--run-reference RUN_ID:INVOCATION`.
Parent lookup alone uses `identity startup` before child registration.
Canonical identity context is bounded to 3,072 bytes; ordinary task/policy
appends are not carried through the hook. The manifest sets 18 seconds and
4,096 tokens for each hook. Repeated callbacks reconstruct context rather than
using a permanent once-per-session sentinel.

## Real disposable registration

```text
pnpm test:codex-hooks:live
```

This CLI-only fixture loads the **current Harnesses checkout** and Millhouse
`62723b7b1820c7e1723de4a2ff985b069871159e` in an isolated workspace/runtime.
It proves real fresh/recovered identity, startup/resume/clear/compact,
subdirectory and linked-worktree routing, native child parentage, and bounded
conflict/unavailable-runtime failure. It starts and retires only its own
fixture runtime. No installed package or shared Weaver is modified.

Managed publication, request replay, target exclusion, exact invocation fencing,
atomic performed/run evidence, unknown effort, missing-startup settlement,
retry and real-thread continuation are covered by the disposable Clojure
`native-session-test` suite. Provider tests separately preserve ordinary task
and ordered developer appends across fresh and resumed launch preparation.

These tests prove host context transport and persisted registration—not real
model obedience, desktop runtime certification, downstream deployment, or
combined Codex/Pi acceptance. Those remain separately owned release work.

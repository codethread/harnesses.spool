# Codex startup-hook conformance fixtures

This suite pins the packaged Codex identity adapter to **Codex CLI 0.154.0** and the Millhouse native startup API pinned at `f17ad387b2825887b736cab597b33af84cff13cb`. All paths (home, config, state, cache, temp, cwd) are disposable; only `PATH` is inherited. Credentials, identity/run/bootstrap state, shell startup scripts, and ambient settings are excluded unless a case deliberately adds a hostile value.

It does not use a shared Millstrand workspace or any desktop application. Run the bounded default suite from the repository root:

```text
pnpm test:codex-hooks
```

The command requires Node.js, Bash, `jq`, either `lockf` or `flock`, and exactly `codex-cli 0.154.0` on `PATH`. Updating that pin requires reviewing the selected release's generated hook schemas and re-running every fixture. A separate live acceptance command is documented below.

## Pinned Codex contract

The sanitized payloads reproduce the required fields in the `rust-v0.154.0` generated schemas:

| Event           | Event-specific adapter inputs                                                           |
| --------------- | --------------------------------------------------------------------------------------- |
| `SessionStart`  | `session_id`, `cwd`, and `source`; source is `startup`, `resume`, `clear`, or `compact` |
| `SubagentStart` | Parent `session_id`, `cwd`, `turn_id`, `agent_id`, and `agent_type`                     |

Both carry `transcript_path`, `model`, `permission_mode`, and the literal `hook_event_name`. `SubagentStart` has no `source` field. Its native child key is `codex-child:v1:<base64url(parent UTF-8)>:<base64url(agent UTF-8)>`; using the parent session ID or child agent ID alone is invalid.

The four generated input/output schemas are vendored under `schemas/` from OpenAI Codex tag `rust-v0.154.0` (commit `6b9826e3aa`). The runner validates every payload and response against those pinned required fields, constants, enums, and types.

Successful output is exactly one JSON object:

```json
{
  "hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": "..."
  }
}
```

A child uses `SubagentStart`. In 0.154.0, `additionalContext` is extra **developer context**, not a replacement system prompt. The packaged handlers set `additionalContextLimit` to 4,096 tokens and a reviewed 18-second timeout, while `identity.sh` rejects context above 3,072 bytes, so required identity or managed guidance is never intentionally sent through Codex's spill-preview path. Unmanaged failures return bounded warnings without an identity instruction; a selected managed native-v1 failure returns a bounded stopping response after attempting its fenced failure receipt.

## Millhouse adapter contract

`hooks/identity.sh` invokes the landed API with argv-safe arguments and a three-second request deadline:

```text
strand [--workspace EXPLICIT_WORKSPACE] --cwd PAYLOAD_CWD --timeout 3s \
  identity startup codex NATIVE_SESSION_ID [--model MODEL] \
  [--parent-identity PARENT_NAME]
```

The adapter accepts only the exact CLI response keys `operation`, `identity`, `strand-id`, `result`, and `instruction`. `operation` must be `identity startup`; `result` must be `minted`, `recovered`, or `attached`; and `instruction` must equal Millhouse's canonical identity instruction for the returned name.

`MILLSTRAND_CODEX_WORKSPACE` selects an explicit host/user workspace. Otherwise an unmanaged `MILLSTRAND_WORKSPACE` is accepted, then passed explicitly. With neither, Strand discovers from the payload cwd, including subdirectories and linked worktrees. The hook never creates a workspace or starts, restarts, or stops Weaver.

A root with `MILLSTRAND_AGENT_ID` or `MILLSTRAND_RUN_ID` but no guidance document stays on the legacy managed transport: no startup call, mint, or native injection occurs. The exact `transport: legacy` document does the same. The disabled explicit native-v1 branch additionally requires `MILLSTRAND_MANAGED_BOOTSTRAP`, fetches a frozen bundle with `agent startup`, strictly validates its echoed launch fences/canonical identity/RFC 8785 digest and 1 MiB response bound, renders one complete 3,072-byte contribution, and records `agent guidance acknowledge` immediately before hook return. Failures use `agent guidance fail` when the metadata safely identifies the current attempt. Child events do not consume root guidance or adopt inherited parent hints; those variables are removed before Strand execution. The child independently resolves the parent session, then starts its composite native key with `--parent-identity` so Millhouse records idempotent parentage.

Each startup, resume, clear, and compact event re-runs identity resolution. There is no permanent once-per-session sentinel. Before calling Strand, `identity-sources.sh` asks Codex 0.154.0 `hooks/list` for the effective cwd configuration and requires exactly one enabled identity handler for the current event. Because the running identity handler proves the invoking host's hooks feature is enabled, the nested query explicitly preserves that effective value; Codex's process-local `--enable hooks` would otherwise be lost. This source count is independent of process overlap and ignores ambient `PLUGIN_ROOT`, so staggered copies and plugin/user combinations fail visibly before identity context is produced. A transient per-event `lockf`/`flock` OS lock remains as a second layer for overlapping execution and is released by the kernel even after `SIGKILL`. Idempotent binding alone would not prevent duplicate context.

## What the suite proves

`run.mjs` performs three bounded layers:

1. It replays every payload through the packaged production `identity.sh` after an explicit fixture-only configured-source stage, using CLI-shaped deterministic test doubles. Unmanaged coverage includes startup/resume/clear/compact, child composite keys and parentage, inherited-hint removal, explicit and cwd-discovered workspace routing, legacy managed skip, exact canonical response parsing, context bounds, malformed/empty/multiple/flooding/nonzero/no-workspace/conflict failures, missing Strand, invalid payloads, bounded response-file cleanup, removable forced-timeout artifacts, concurrent duplicate execution, and healthy replay after host `SIGKILL` abandons the lock file. Managed coverage reconstructs startup/resume/clear/compact and receipt replay without a sentinel; preserves repeated frozen append positions and one current-run footer; checks scrubbed child environments; and rejects malformed, duplicate-key, flooding, digest/session fence, acknowledgement, and backend failures without model turns.
2. It starts Codex app-server 0.154.0 in disposable configurations and calls `hooks/list`. This proves the manifest's explicit `.codex-plugin/hooks/hooks.json` path, the separate observational capture and identity handlers, `SessionStart` plus `SubagentStart` identity registration, handler time/context limits, untrusted and trusted states, feature/plugin disablement, missing-hook warnings, source metadata, and cwd routing. The no-model preflight is exercised against both required `sessionStart` and `subagentStart` hook facts; exact 18-second/4,096-token handler limits; trusted, untrusted, changed, missing, and duplicate hooks; complete supported selectors; rejected unknown arguments; and competing-instruction profiles without forcing `features.hooks=true`.
3. It runs actual Codex 0.154.0 `exec` against a local HTTP/SSE response fixture. With hooks disabled on disk, one package launched with `--enable hooks` reaches fake Strand and contributes developer identity context on both fresh startup and resume. The ordinary single-package case reaches Strand once and contributes one developer identity message. Two valid package copies, then one package plus a delayed user registration inheriting that package's `PLUGIN_ROOT`, are each listed as two sources; staggered host execution makes zero Strand calls and contributes zero identity messages. The production hook's direct diagnostic is also asserted for both duplicate configurations.

The fake does not prove Millhouse internals; the landed identity spool owns mint/recover/attach atomicity and its CLI schema. These fixtures prove that the adapter calls that schema without launcher ownership hints and forwards only canonical context.

## Live disposable acceptance

Run the production hook against real Strand and the live Millhouse startup API pinned at `f17ad387b2825887b736cab597b33af84cff13cb`:

```text
pnpm test:codex-hooks:live
```

This CLI-only script starts its own foreground Mill under isolated state, installs one copied harness package in an isolated `CODEX_HOME`, initializes a short-lived Git project and `.millstrand` workspace, activates the pinned identity spool, and starts/stops only that disposable Weaver. It never addresses or changes the user's global Mill or a shared workspace. It verifies configured-source preflight plus fresh identity minting, startup/resume/clear/compact recovery, cwd discovery from a nested directory and linked worktree, child parentage (including the stored `parent-of` edge), and bounded conflict/unavailable responses through production `identity.sh`. All temporary homes, caches, Gitlibs, state, sockets, and graph data are removed on exit.

The command requires Bash, Codex CLI 0.154.0, Git, `jq`, `mill`, and `strand`, plus network or cached Git access to the pinned Millhouse and Millstrand commits.

## Desktop intent and validation limit

OpenAI documents plugin enablement and bundled lifecycle hooks for supported local clients, including Codex CLI and Codex in the ChatGPT desktop app. Hook files must exist locally and remain subject to trust; web installation alone does not deploy them.

No desktop app, installed plugin cache, existing conversation, or real provider is opened, restarted, reloaded, or modified by this suite. Desktop execution is intended by the documented package contract but is not runtime-certified and is not a gate. The local SSE fixture captures actual model-bound input and proves Codex 0.154.0 developer-context transport, not real-model inference or obedience.

Official references:

- <https://developers.openai.com/codex/hooks/>
- <https://developers.openai.com/plugins/build/plugins/>
- Exact inspected source: OpenAI Codex tag `rust-v0.154.0`, commit `6b9826e3aa`

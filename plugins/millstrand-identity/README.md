# Millstrand identity adapters

This plugin owns the native Codex and Pi integration data required to bind a
host session to a Millstrand identity. It does not own application-specific UI.

## Pi

Install this repository as a Pi package or add its package root to Pi settings.
The extension resolves identity and managed-guidance data during
`session_start`, then emits plain data on `pi.events`:

- `MILLSTRAND_IDENTITY_CONTEXT_EVENT` — the active session identity, or `null`;
- `MILLSTRAND_IDENTITY_STATE_EVENT` — pending, bound, suppressed, or error state;
- `MILLSTRAND_GUIDANCE_CONTEXT_EVENT` — managed selection and fetched bundle;
- `MILLSTRAND_GUIDANCE_FAILURE_EVENT` — a consumer reports that rendering or
  handoff failed, causing subsequent model input to remain blocked.

Import the public constants, types, parsers, child-environment helper, and
managed handoff functions from `@codethread/harnesses/pi/millstrand-identity`.
Consumers decide whether identity appears in a prompt, statusline, debug panel,
or another UI. A prompt owner that handles `native-v1` must render the frozen
bundle, record the handoff acknowledgement, and emit the failure event if that
process fails.

The extension accepts:

- `--millstrand-workspace <dir>` for explicit workspace routing;
- `--millstrand-identity <name>` as an assertion for an already-bound session;
- `--debug-millstrand-identity` to print the resolved state as JSON and exit.

A native Pi child must use `buildMillstrandChildEnvironment` so inherited root
ownership is scrubbed and only parent attribution plus workspace routing pass to
the new session.

## Codex

Enable and trust `millstrand-identity@harnesses` from this checkout. The packaged
SessionStart and SubagentStart hooks run only in the launch project's canonical
Millstrand workspace, including linked Git worktrees. In other projects they do
nothing; inherited workspace configuration does not bypass the gate.

The hook awaits `agent native-startup codex ACTUAL_ID --model MODEL`, composing
Harnesses registration with Millhouse Identity startup. Managed roots pass only
`MILLSTRAND_RUN_REFERENCE=RUN_ID:INVOCATION`; direct sessions register an external
run without a seat, alias, task or process custody. Actual model is recorded;
unavailable Codex effort is explicitly `harness/observed-effort=unknown`, with a
managed selected effort retained when present. The marker is not a launch option.

Startup/resume/clear/compact reconstruct canonical developer `additionalContext`.
Children use the parent-session/agent composite and parent attribution, never the
parent's run reference. Ordinary task/policy/alias appends remain on normal launch
paths. No Codex bootstrap/guidance transport, reservation or legacy fallback
remains. Duplicate injectors and startup failures stop before model work when the
hook runs. Source installation and runtime activation are separate operations.

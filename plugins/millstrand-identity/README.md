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

The extension stays inert outside a Millstrand project. Without an explicit
workspace, a session whose canonical project root carries no `.millstrand`
publishes no identity or guidance and reports a `suppressed` state naming the
missing workspace. Subdirectories and linked worktrees resolve through the
canonical Git root, matching Strand's workspace discovery, so every entry point
into one project reaches the same workspace.

## Codex

Add this checkout as a local Codex marketplace and enable
`millstrand-identity@harnesses`. The plugin registers one `SessionStart` and one
`SubagentStart` hook. Hooks bind the actual native ID, reject duplicate
injectors, and return the canonical identity instruction as additional developer
context.

The optional managed-guidance adapter remains subject to Harnesses capability
admission. Installing these files alone does not enable `native-v1`.

The hook stays inert outside a Millstrand project. Without an explicit
`MILLSTRAND_CODEX_WORKSPACE` or unmanaged `MILLSTRAND_WORKSPACE`, a project whose
canonical Git root carries no `.millstrand` exits before the configured-source
probe, the OS lock, and Strand, and returns no context. Managed `native-v1`
launches keep their launcher-provided workspace and routing.

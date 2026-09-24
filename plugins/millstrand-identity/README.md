# Millstrand native identity adapters

Harnesses owns native identity data and run registration. It does not own a
consumer's prompt layout or UI.

Both adapters require canonical Git project discovery and a `.millstrand`
directory at the Git common root. Without Git, Millstrand is inert—even when
`.millstrand` exists in the launch directory. Inherited workspace settings and
managed run references never bypass this gate. A linked worktree uses its main
repository's workspace, not a worktree-local `.millstrand`.

## Pi

Install this repository as a Pi package. The standalone extension awaits native
registration at `session_start` and contributes the canonical identity instruction
through `before_agent_start`. Ordinary task and ordered alias/user appends remain
ordinary Pi launch prompts. There is no Pi identity reservation, bootstrap
selection, or generated identity append flag.

Startup derives `.millstrand` from the launch project's canonical Git common
root, including linked worktrees. Outside such a project it makes no Strand call
and contributes no identity. Inherited workspace/identity settings do not select
a project. Inside a project registration failure is visible and blocks input and
provider requests; it never fabricates an identity.

The callback is:

```text
strand --workspace CANONICAL_WORKSPACE --cwd SESSION_CWD \
  agent native-startup pi ACTUAL_SESSION_ID \
  --model PROVIDER/MODEL --thinking-level HOST_LEVEL
```

Optional `--run-id` correlates an existing managed run. Pi managed launches export
only `MILLSTRAND_RUN_ID` for this purpose, not identity/bootstrap configuration.
The server validates provider, actual session, cwd and the active invocation,
then records native identity and `performed` provenance. The host awaits this
before model work. Missing callback makes a managed completion fail as bootstrap,
while preserving actual process settlement.

Without a managed reference the same API registers the already-running session,
without spawning, claiming work or inventing an alias. Repeated callbacks reuse
the provider/session registration. Direct records have `harness/mode=external`
and `harness/ownership=external`; they contain no managed attempt, process custody
or settlement evidence. Harnesses cannot stop that external process.

`harness/observed-model` holds actual model metadata.
`harness/observed-effort` holds actual thinking level, the authoritative managed
selection when the host supplies none, or the explicit string `unknown`.
Consumers display that marker as **Unknown**. It is not `harness/effort` and is
never sent as a provider reasoning option. Registration does not broaden alias
visibility for direct sessions.

Same-session resume/reload retains identity. A fresh session has a distinct key.
Native fork parent headers supply `--parent-native-session-id`; child helpers use
`buildMillstrandChildEnvironment` to scrub inherited ownership/run correlation
and pass only the resolved native parent identity. A fork carrying its parent's
run reference is registered separately, not attached to the parent's invocation.

### Consumer-owned prompt rendering

Import `createMillstrandIdentityLifecycle` from
`@codethread/harnesses/pi/millstrand-identity` instead of loading the standalone
extension alongside a prompt owner. Await `sessionStart(ctx)`, then render the
bound `identityState.instruction` exactly once in the reconstructed system prompt.
The lifecycle itself emits data and installs no prompt renderer.

The public identity/context event names and bound state remain stable. The
consumer's existing ordinary append rendering remains untouched. Register its
input and provider-request handlers with the lifecycle so failed startup stays
blocked. `guidanceContext` is unmanaged: Pi no longer selects or fetches the old
managed-guidance protocol.

Do not install the standalone extension and a composed lifecycle together.
`--debug-millstrand-identity` resolves registration, prints state and exits without
a model request.

### Isolated verification

The host tests load this checkout explicitly with `--no-extensions --extension`,
use disposable projects/agent directories, a no-model Strand boundary fixture,
and do not modify installed packages. Clojure tests exercise real Identity and
run persistence in disposable Weaver worlds.

Set `PI_PROMPT_OWNER_SOURCE` to the consumer's system-prompt entrypoint when
running the host test to additionally exercise its actual renderer. The fixture
copies that entrypoint to a disposable directory and redirects only adapter
imports to this checkout; the consumer repository remains read-only.

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

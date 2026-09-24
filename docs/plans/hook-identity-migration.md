# Native startup identity for Codex and Pi

> Historical plan, superseded on 23 September 2026 by native identity epic
> **j6fae** and provider features **z5qkr** / **luvpl**. The current contract is
> documented in the repository README. In particular, pre-launch reservation,
> compatibility transports, identity-only direct startup, environment routing
> overrides and hook-delivered ordinary guidance below are no longer the Codex
> or Pi design. Native startup registers direct/managed runs; ordinary guidance stays
> on launch paths. Preserve this document only as historical design evidence.

Design/backlog: **nsl12** · implementation epic: **k5zd2** · author: **steady-gentle-fox**

## Decision and boundaries

**Proposal:** native startup owns identity resolution. A user opens a native
Codex desktop session; its hook obtains the real session ID and workspace,
recovers or mints a Millhouse identity, and injects the instruction. Pi uses its
extension lifecycle for the same protocol. Neither path requires our launcher,
`MILLSTRAND_AGENT_ID`, `MILLSTRAND_RUN_ID`, a run record, or a reservation.

**Validation constraint:** design for desktop startup, **test CLI only**. Do not
interact with, close, reopen, restart, or reload plugins in the desktop app or
current conversation. Desktop support below is documentation-grounded intent,
not a live runtime certification. That validation limit is **not a blocker**.
Future implementation tests use Codex/Pi CLI and disposable session/workspace
fixtures. This planning task ran no provider smoke tests or workspace-backed tests.

Only **Codex and Pi** receive this migration. Claude and Cursor are
maintenance-only: preserve existing startup, Weaver activation, delegation,
and prompt transport. No parity work, new hooks, availability changes, or
Claude/Cursor hook prerequisites. The coordinator already completed policy
card **3161v**, main commit **1154d21**; this backlog does not duplicate it.

Managed CLI identity reservation remains an **optional compatibility path**.
It must not become a desktop prerequisite. Keep managed publication invariants
rather than redesigning the scheduler around late identity readiness.

Labels used below: **Verified** means inspected source or official documentation;
**Inferred defect** is a consequence of source paths, not a reproduced paid run;
**Proposal** names work still to implement. Proposed command/field names are not
claims about today's CLI.

## 1. Canonical startup lifecycle

**Proposal, desktop intent and unmanaged CLI equivalent:**

1. The native host creates or loads a session, then activates its installed,
   enabled, trusted startup hook/extension.
2. Adapter reads the **actual native session ID**, startup reason, and session
   working directory from the host API. Never use a provisional launcher UUID,
   transcript filename, dialogue-capture `unknown`, or current plugin directory.
3. Resolve the Millstrand workspace: explicit per-host/user workspace selection
   when configured; otherwise let Strand discover the canonical Git-worktree
   workspace from the payload cwd. Pass explicit `--workspace` when overriding
   and `--cwd` for the session directory. Do not assume exported
   `MILLSTRAND_WORKSPACE` is automatically read by the dispatcher. For managed
   callers, translate the validated launcher value into `--workspace` explicitly.
4. Call the proposed **`strand identity startup <harness> <native-session-id>`**
   (identity-only spool; no dependency on Harnesses activation). Its transaction
   resolves a valid session-scoped supplied identity, else the existing native
   binding, else a new identity. Return name, identity strand ID, resolution
   outcome, and canonical context text.
5. Adapter emits native context. Codex uses SessionStart `additionalContext`;
   Pi composes the Identity spool's returned instruction into its effective
   system prompt. That instruction distinguishes `--owner` for `kanban claim`,
   `--by-identity` for actor attribution, and `--identity`/`--parent-identity`
   for native-session references. Include explicit workspace guidance when cwd
   discovery alone would not route there.
6. Subsequent Strand operations carry the friendly name explicitly. A desktop
   agent can delegate through Strand without ever having a managed parent run.
   The operation source stores `identity/by-identity`; the child identity gets
   `performed` to its run. Native `parent-of` remains an explicit
   `--parent-identity` relation. Work cards and process custody remain separate.

A plain desktop session receives identity/routing context, **not an invented
assignment**. Native project instructions remain native. Only a validated managed
run contributes frozen assignment/policy and accumulated user/alias guidance.

### Resolution and binding contract

**Verified:** current `identity/bind!` already accepts harness/session without a
run and mint/recovers by that pair [I1]. Missing functionality is mainly safe
supplied-identity attachment, optional reservation, and integration—not a new
identity service.

**Proposal:** retain the friendly-name identity strand as owner; store a single
canonical native binding per logical native session. A managed reservation can
exist without a confirmed native binding. Do not turn every native restart into
a new actor or introduce a global identity broker.

| Input state | Resolution |
| --- | --- |
| No supplied identity, no binding | Mint one name and bind actual native session. |
| No supplied identity, existing binding | Recover it, including startup/resume/reload replays. |
| Supplied identity already bound to this session | Honor it. |
| Supplied reserved identity, validated managed attachment | Attach actual session to that reservation once. |
| Unknown/malformed/ambiguous explicit identity | Fail before writes; do not silently mint. |
| Supplied identity conflicts with native binding | Fail before writes; never steal/overwrite a binding. |
| Ambient inherited name not valid as a session-scoped supply | Discard as ownership input; resolve this child's own session. |

“Honor when valid” is not arbitrary renaming. A bare friendly-name environment
variable does not authorize adopting an identity bound to a different session.
A user wishing to supply an identity must use an explicit host setting scoped to
that native session, or the managed attachment contract. Defaults need no setting.
Keep the existing `expected-identity` meaning as an assertion, not an implicit
“adopt this name” operation. Validate all constraints before minting or edges.

Serialize lookup/mint/attach so simultaneous hooks converge. Replays must not
multiply identities, `performed`, or `parent-of` edges. Optional run/parent targets
must resolve before writes. Identity uniqueness is **workspace-local**, as today;
include workspace in adapter caches and diagnostics. No-workspace, ambiguous
multi-root selection, or inaccessible workspace is an explicit unbound/error
result, not permission to create a workspace or use some global default. Never
start/restart Weaver from the hook. Same native session must retain its original
workspace association across cwd changes; on ambiguous routing request explicit
selection rather than minting again elsewhere.

### Native children and forks

**Verified:** Codex SubagentStart uses parent `session_id` plus `agent_id` [D1].
Pi children have their own session ID and the current subagent launcher spreads
all of `process.env`, then sets `PI_SUBAGENT=1` [P3].

**Proposal:** Codex child identity key is an unambiguous encoded pair of parent
session ID and agent ID, distinct from the root key; keep harness `codex`.
Use SubagentStart's context channel for the child, not a root SessionStart bind.
Pi uses the child's actual session ID. Both resolve parent attribution separately
and never use parent's `MILLSTRAND_AGENT_ID` or run ID as child ownership. Scrub
identity/run/bootstrap transport hints in Pi's native spawn path, carrying an
explicit **parent** reference only. Preserve resource settings and resume IDs.
New/forked native sessions get new identities; same-session reload/resume retains
identity. Copied parent prompt blocks are superseded by the child's current block.
A native child with no available injection channel must not claim the parent name.
No assumption that `agent_type` alone proves child status.

## 2. Native APIs and compatibility matrix

**Verified documentation, not execution:** fetched official references on
2026-09-13. Upstream docs can describe newer releases than installed packages;
CLI fixture card vz8a2 checks the exact selected version. Observed standalone
Codex CLI is **0.154.0**. Agents dependency manifest pins Pi **0.84.4** [P4].
Read-only app metadata showed ChatGPT **26.903.71938**, not a separate Codex.app;
no app was launched or manipulated. Do not equate these products/build numbers.

| Host | Startup identity / response | Evidence and scope |
| --- | --- | --- |
| Codex in desktop local execution | SessionStart `session_id`, `cwd`, `source`; JSON `hookSpecificOutput.hookEventName="SessionStart"`, `additionalContext` | OpenAI explicitly documents local plugin enablement in CLI **and Codex in desktop**, and bundled lifecycle hooks [D2]. Intended primary integration. No live desktop validation; CLI tests only. |
| Codex CLI, unmanaged or managed | Same documented SessionStart contract; startup/resume/clear/compact; SubagentStart carries `agent_id` | `additionalContext` and plain stdout are **developer context**, not replacement system prompt [D1]. New integration, version-qualified CLI tests. |
| Pi CLI / extension-enabled host | `session_start`; `ctx.sessionManager.getSessionId()`, `ctx.cwd`; return `systemPrompt` from `before_agent_start` | Real effective system-prompt composition, not merely a persisted chat message [P1, P2, D3]. Pi extension support is not evidence of a separate desktop/mobile product. |
| ChatGPT ordinary Chat/mobile/iOS | No demonstrated local hook/Strand execution | Out of scope. A plugin UI, remote MCP connection or “app” label is not proof of local hooks. |
| Cloud/remote environments | Only where that execution environment actually has hook files, Strand and selected workspace access | Not claimed by this migration. Installing a plugin on the web does not deploy scripts [D2]. No cloud bridge proposed. |
| Claude / Cursor | Existing provider CLI flags / existing Cursor env-fed hook | **Maintenance only** [M1]. No startup identity migration or new-capability gate. |

Codex `systemMessage` is UI/event-stream warning, **not model context**. Use the
explicit hook-specific response with JSON-only stdout. Hooks from multiple sources
all run, often concurrently; installation alone does not grant hook trust. Avoid
adding the same injector to both user config and plugin. Current docs describe
~2,500-token default additional-context spilling; identity and frozen policy must
not silently become a truncated preview. Validate bundle size against the selected
host limit, explicitly configure a tested limit where available, and report an
oversized required bundle rather than claiming faithful delivery [D1].

Pi's existing system-prompt extension rebuilds from `systemPromptOptions`, including
append text. A new earlier handler that merely appends to `event.systemPrompt`
can be overwritten. Compose within the existing owner or a deliberately ordered
shared contribution; do not create a second competing prompt owner [P1].
`pi.appendEntry` persists data but does not inject context; returning a custom
message adds conversation content, not system instruction precedence [D3].

## 3. What current source proves

### Reusable spool versus native package

**Verified:** reusable Harnesses source supplies managed lifecycle, provider
commands and the small bundled Cursor hook [H1–H5, M1]. It does **not** install
Codex/Pi identity hooks. The separate agents repository owns Codex plugin hooks
and Pi extensions [P1–P4, N1]. Existing Codex hooks only capture dialogue/file
activity; Pi dialogue capture only writes logs. Neither calls Strand identity.
Searching both source trees and installed Codex plugin 0.3.0 found no startup
identity reconciliation.

**Inferred packaging defect:** source and installed Codex 0.3.0 manifests declare
skills but no `hooks` field. Hook JSON lives under `.codex-plugin/hooks`, whereas
current official default discovery is root `hooks/hooks.json` [N1, D2]. Therefore
that layout alone does not establish that capture hooks load. New integration
must explicitly reference the packaged hook path (or use the default layout),
then prove discovery in CLI fixtures. Do not patch installed cache as source.

### Managed publication invariants

**Verified:** `create!` allocates a UUID unless given one and checks active session
and target reservations. `commit-run!` creates an unpublished run, binds the
worker identity and `performed` provenance, stores raw operation actor evidence,
expands `{{RUN_ID}}`/`{{AGENT_ID}}`, and only then publishes [H1, H2]. Assignment
policy/context and system guidance are
frozen; native resume keeps concrete provider, cwd, target, native session and
settings instead of re-resolving an alias [H2, H4]. Both execution paths export
reserved identity/run/workspace values [H3].

The README's statement that prompt injection is not replayed on resume is stale.
Current Codex and Pi provider source explicitly replays pinned guidance on resume
[H5]; preserve actual source behavior, then correct documentation.

### Codex continuity: verified path, inferred user-visible failure

1. **Verified:** new Codex run UUID is bound as if native before launch [H1, H2].
2. **Verified:** Codex only receives an existing session ID on resume; new thread
   IDs are provider-created. Headless finish extracts `thread.started.thread_id`
   [H5]. New interactive runs have no parseable thread stream.
3. **Verified:** `finish!` and `settle-outcome!` update run `harness/session-id`, but
   neither invokes an identity attachment/rebinding function [H2]. Current hooks
   do not repair this elsewhere [N1, P2].
4. **Inferred defect:** native resume of the now-real thread calls `bind!` with
   predecessor friendly identity expected. With no native binding for that thread,
   `bind!` mints another identity **before** checking expectation, then throws.
   This can leave an orphan identity and unpublished continuation [I1, H1].

This is a source-grounded defect hypothesis, not a paid end-to-end reproduction.
The plan calls for a deterministic lifecycle regression, not an agent launch to
prove it. Existing duplicate bindings must not be silently merged or stolen.

## 4. Optional managed CLI adaptation

**Proposal:** keep identity durable before managed publication, but represent a
Codex provisional UUID as a **reservation**, not a confirmed native binding.
Millhouse supplies reserve/attach primitives; Harnesses supplies optional managed
startup orchestration (proposed `strand agent startup`) for its own run schema.
Desktop hooks call the identity-only operation without needing Harnesses.

Managed startup validates run, concrete provider, cwd, current attempt/invocation,
root-versus-child scope and writer reservation; obtains the reserved identity;
attaches the real native session; records startup binding evidence and returns
frozen prompt contributions. Pi's pinned session must equal the host's actual ID.
A run/attempt can attach once; repeat same ID converges, different ID conflicts.
Bare ambient environment is insufficient: native child events and `PI_SUBAGENT`
never consume root bootstrap ownership. An unmanaged nested CLI session whose
native ID differs from the already-attached parent ignores inherited parent hints.
For the initial managed root, bootstrap is scoped to the launch invocation and
expected root session/host; do not accept arbitrary run IDs from environment alone.
This is correctness fencing in a trusted local workspace, not an authentication
system against a malicious local process.

Use the existing invocation fence and publication lock rather than a second job
queue. Finish and late settlement must call the same attachment operation when
valid native evidence arrives, preserving hook-confirmed evidence even if the
interactive provider cannot rediscover it from stdout. A stale outcome cannot
rebind a newer attempt. Native binding is not proof that custody has settled or
that the session's history is resumable; keep those evidence checks separate.
Explicit legacy repair may attach the recorded run identity to its observed native
ID only if unoccupied and consistent. Conflicting legacy identities require a
reviewed repair decision, not a bulk startup sweep.

`--after` is a fresh session/identity; native resume keeps identity. Non-resume
retry currently changes session UUID without rebinding [H1, H2]; include coherent
identity replacement and placeholder refresh for Codex/Pi retries. Do not widen
that work into Claude/Cursor parity. Keeping prepublication identity avoids a
late-mint readiness protocol that would affect assignment claims, parentage,
reservations, scheduler visibility and frozen prompts all at once.

## 5. Guidance transport, coexistence, failure

**Proposal:** preserve public user inputs and durable content; migrate delivery.

| Field / transport | Codex/Pi change |
| --- | --- |
| `identity/prompt` and `harness/appended-system-prompts` | Retain durable source; native adapter composes identity then ordered contributions. |
| Alias `:append-system-prompt`, run `--append-system-prompt`, workflow/reviewer additions | Keep acceptance API and parent→child→run ordering; no user content lost. |
| Codex generated `--config developer_instructions=...` | Remove only in explicitly selected native-v1 mode. Keep model/effort config. |
| Pi generated `--append-system-prompt` contributions | Remove only in native-v1 mode; existing renderer receives frozen bundle instead. |
| Main `harness/prompt`, stdin/positional task | Keep as user task. Do not move it wholesale into startup context. |
| Assignment policy/context and RUN_ID substitutions | Retain frozen prose; render current continuation run ID on each native resume. |
| Claude append flags / Cursor prompt env and hook | **Unchanged**, including maintenance delegation. |

A single transport choice is recorded on each managed invocation: legacy CLI or
native-v1. Adapters installed before cutover recognize old managed run env and
skip new injection/minting; unmanaged sessions with no run hints participate
immediately. Only explicit versioned managed bootstrap activates native-v1.
Provider capability preflight checks the chosen adapter/version/trust/config,
not merely whether a package directory exists. New spool + old hook stays legacy
or rejects explicit native-v1 before launch. New hook + old spool stays legacy.
There is no “send flags and hope the hook deduplicates later” fallback.

On native-v1 resume, re-inject the frozen bundle with the new run ID and identify
it as the current binding, superseding historical identity/run guidance. Pi
rebuilds exactly one owned block each turn. Codex re-emits on context reconstruction
(startup/resume/clear/compact); old transcript reminders may remain historical,
so promise **one current contribution per event**, not transcript erasure. Never
use a permanent session-level “already injected” sentinel. Duplicate injector
registrations must be diagnosed and removed; same-key bind idempotency alone
cannot stop two independent hooks both returning context. Raw extra argv that
competes with migrated prompt fields should be rejected in native mode with a
clear route to the supported appended-guidance API, not silently rewritten.

Failure policy:

- **Unmanaged hook cannot resolve workspace/Strand/Weaver:** visible warning and
  unbound guidance; no name invented, no identity-bearing actions as somebody
  else, no hidden retries or automatic infrastructure startup. Plain native use
  may continue without claiming Millstrand participation.
- **Explicit identity conflict/invalid managed bootstrap:** loud error before
  writes; never adopt a conflicting name or mint to hide it.
- **Managed native-v1 bootstrap fails:** mark bootstrap failure; use native stop
  response where actually supported and existing custody stop/settlement path.
  Do not accept a run as successfully integrated without its startup binding/
  context acknowledgement. An acknowledgement proves adapter processing, not
  universal model compliance.
- **Hook absent/disabled/untrusted:** no hook can report its own absence or
  guarantee a pre-model block. Require preflight and CLI negative tests; use
  explicit legacy transport for unverified managed combinations. Do not claim
  fail-closed protection against all host failures. No provider auto-retry solely
  to disguise absent identity/context.
- **Required bundle exceeds host limit:** reject/report before claiming delivery;
  no silent clipping, link-only replacement of required policy, or lost appends.

These are bounded adapters and APIs, not a daemon, hook broker, context sync
service, or desktop readiness protocol. Do not introduce live desktop checks.

## 6. Executable backlog and integration order

Epic **k5zd2 — Native startup identity for Codex and Pi** contains the following
**actual pending, unclaimed features**, each with its own repo scope, entry points,
acceptance cases, validation and handoff contract:

| ID | Feature | Depends on |
| --- | --- | --- |
| **vz8a2** | Codex CLI startup hook conformance fixtures; desktop intent from docs | — |
| **sfc79** | Millhouse native startup identity API without launcher state | — |
| **cs3eu** | Codex desktop-intended native hook; validate CLI only | vz8a2, sfc79 |
| **d490o** | Pi native identity lifecycle and owned prompt composition | sfc79 |
| **bxxp5** | Optional managed binding, reservation, native continuity repair | sfc79 |
| **g23us** | Managed Codex/Pi native guidance transport and coexistence | cs3eu, d490o, bxxp5 |
| **ta2ip** | Cross-repo acceptance, releases and dependency pin advancement | g23us |

Verified via live Strand subgraphs and ready query: **seven** epic membership
edges, exactly **eight** dependency edges, no cycle, all seven features pending
and unclaimed. Initial ready set: **vz8a2, sfc79**.

Codex adapter needs its CLI contract; Pi does
not. After identity API lands, Pi, Codex (when fixtures ready), and optional
managed compatibility can proceed independently. Desktop-intended adapter work
has **no dependency on managed compatibility**. Final transport depends on all
three because it removes the former delivery path. Release depends on transport
and therefore transitively on all implementation prerequisites.

### Agent workflow

1. Coordinator selects genuinely ready features with `strand ready --query
   kanban-epic-pending --param epic=k5zd2`. Delegate through Strand only when
   implementation is authorized; nothing in this planning run launches workers.
2. Each worker reads its card and owning repo's AGENTS.md, claims with its own
   authoritative identity, branch and worktree, decomposes tasks and notes
   decisions as it works. Cross-repo source pointers are not permission to edit
   another active checkout. Use repository worktree tooling and review process.
3. Worker returns focused cold-test/fixture evidence, changed public contracts,
   and reviewed commit/release SHA for coordinator acceptance. Mere process
   termination does not finish feature/epic. Only close a prerequisite when its
   accepted API/commit is actually consumable by the dependent feature.
4. Land Millhouse identity API first; freeze exact CLI JSON/attachment contract.
   Native agents package can consume identity-only API independently of managed
   Harnesses. Land adapter/package revisions with correct Codex manifest and Pi
   extension registration. Never publish by modifying installed plugin caches.
5. Land Harnesses optional compatibility against the released identity SHA,
   then native transport after adapter availability. Test standalone library
   resolution separately from dogfood workspace resolution.
6. Integration advances library identity pin and workspace identity override;
   inspect shared Codethread consumer config pins before its published adoption.
   Do not advance unrelated Workflow/Kanban coordinates gratuitously. Record
   exact compatible identity/spool/agents/CLI versions. Source pin commits and
   running-runtime activation are separate; no Weaver restart without explicit
   approval, and no desktop app/plugin operation under this validation contract.

### Focused acceptance and rollback

CLI-only acceptance must demonstrate: unmanaged first startup with all identity/
run/bootstrap env absent and no run/reservation; repeat/resume same identity;
new/fork distinct identity; explicit valid supply; no-write conflict; unavailable
Strand/Weaver; linked-worktree routing; unmanaged parent delegation via
`--by-identity`; child environment isolation; Codex provisional→actual→resume;
stale/late outcome fencing; ordered appends and frozen policy once; mixed
old/new transport versions. Preserve Claude/Cursor registration and launch/
delegation using existing command fixtures and focused shared-lifecycle tests,
not new hooks or parity. Embedded runtimes remain unpublished; workspace-backed
smokes use disposable explicit `--workspace` worlds. No shared-world tests.

Roll back **delivery before removing adapters**: select legacy managed transport
for new launches, then disable new injection through controlled source/package
configuration when authorized. Preserve identity records and native bindings;
do not erase provenance or revert a repaired native ID. Unmanaged participation
ceases if its hook is removed—there was never a launcher fallback there. Keep
additive identity API readable during rollback. Reverting to an old Millhouse
binary that cannot read the new representation needs explicit migration review;
it is not a safe automatic rollback. Do not operate desktop applications to
exercise rollback; use disposable CLI configurations only.

## 7. Open questions and validation limits

- Exact Codex CLI release behavior for trust, context spilling and SubagentStart
  must be pinned by vz8a2. Official desktop support is documented, but no desktop
  runtime validation will be performed. This is not an acceptance blocker.
- Final API names/schema and representation of optional reservations must be
  finalized in sfc79 while preserving maintenance callers. No global actor or
  cross-workspace identity portability is promised.
- Explicit workspace selection is needed for non-Git/multi-root sessions;
  establish the minimal host configuration, not a guessed default world.
- Managed bootstrap root-scope/child discrimination and retry identity refresh
  need focused CLI fixtures. Environment inheritance alone is not trustworthy
  ownership evidence.
- Duplicate Codex registration and fail-open missing-hook behavior cannot be
  solved by bind idempotency alone. Keep claims narrow and actionable diagnostics.
- Effective instruction roles differ: Codex developer context versus Pi effective
  system prompt. Neither grants permission beyond the user's/harness's policy.

## Source register

Repository-relative references below are rooted at the repository named in each
entry. All source was read without changing the other repositories.

**H1 — Harnesses publication and reservation**

- `src/ct/spools/harnesses/internal/runs.clj:106` — commit-run!, bind before publish,
  parentage, placeholders; `src/ct/spools/harnesses/internal/runs.clj:251` — retry patch.
- `src/ct/spools/harnesses/internal/runs.clj:37` — managed writer reservation query.

**H2 — Harnesses lifecycle**

- `src/ct/spools/harnesses.clj:37` — create! and provisional UUID.
- `src/ct/spools/harnesses.clj:143` — finish!; `src/ct/spools/harnesses.clj:241` — late settlement.
- `src/ct/spools/harnesses.clj:322` — retry!; `src/ct/spools/harnesses.clj:454` — native resume.

**H3 — Managed execution environment**

- `src/ct/spools/harnesses/execution.clj:340` — process-spec.
- `src/ct/spools/harnesses/internal/launcher.clj:23` — workspace; `src/ct/spools/harnesses/internal/launcher.clj:29` — interactive exports.

**H4 — Frozen assignment**

- `src/ct/spools/harnesses/internal/assignment.clj:91` — prompt/identity/run guidance;
  `src/ct/spools/harnesses/internal/assignment.clj:136` — frozen system guidance.

**H5 — Codex/Pi provider transports and native evidence**

- `src/ct/spools/harnesses/providers/codex.clj:144` — developer_instructions;
  `src/ct/spools/harnesses/providers/codex.clj:180` — provisional evidence;
  `src/ct/spools/harnesses/providers/codex.clj:204` — thread.started extraction.
- `src/ct/spools/harnesses/providers/pi.clj:137` — append flags and pinned session.
- `README.md:260` — old resume-injection prose (source behavior takes precedence).

**I1 — Millhouse identity, pinned source**

- `/Users/ct/.gitlibs/libs/millhouse.spools/identity/7cfcb235848c0db6231ba2282430913580d66f2e/spools/identity/src/millhouse/spools/identity.clj:42` — lookup.
- Same file `:72` — bind!, mint-before-expected check; `:113` — current; `:121` — CLI.
- Byte comparison with f487eb42ea9523e8bd405e64a7c319013217d988 implementation matched.
  Read-only local Millhouse implementation at commit 89e5e32f8a948547c233d5dd183bb73f9c5abe4a matches the relevant behavior.
- `deps.edn:5` and `.millstrand/deps.edn:12` — library and dogfood identity pins.

**N1 — Separate Codex plugin, agents repository**

- `/Users/ct/dev/projects/agents/plugins/harness/.codex-plugin/plugin.json:1` — manifest.
- `/Users/ct/dev/projects/agents/plugins/harness/.codex-plugin/hooks/hooks.json:1` — capture registrations.
- `/Users/ct/dev/projects/agents/plugins/harness/.codex-plugin/hooks/capture.sh:1` — logging only.
- `/Users/ct/.config/codex/plugins/cache/agents/harness/0.3.0/.codex-plugin/plugin.json:1` — inspected installed manifest, not edited.

**P1–P4 — Pi integrations, agents repository**

- P1: `/Users/ct/dev/projects/agents/pi/extensions/system-prompt/index.ts:149` — startup;
  `/Users/ct/dev/projects/agents/pi/extensions/system-prompt/index.ts:181` — owned prompt composition.
- P2: `/Users/ct/dev/projects/agents/pi/extensions/dialogue-capture/index.ts:38` — session ID;
  `/Users/ct/dev/projects/agents/pi/extensions/dialogue-capture/index.ts:57` — child session mapping.
- P3: `/Users/ct/dev/projects/agents/pi/extensions/tools/subagent/runtime.ts:348` — inherited environment.
- P4: `/Users/ct/dev/projects/agents/package.json:39` — extension registration; dependency version near file end.

**M1 — Maintenance transports, unchanged**

- `src/ct/spools/harnesses/providers/claude.clj:140` — existing append flags.
- `src/ct/spools/harnesses/providers/cursor.clj:53` — existing prompt env;
  `plugins/cursor/harness/scripts/session-start-sys-prompt.sh:1` — existing context hook.

**D1–D4 — Official/current references**

- D1: https://developers.openai.com/codex/hooks/ — SessionStart/SubagentStart,
  payload/output roles, trust/discovery, concurrency and output limits.
- D2: https://developers.openai.com/plugins/build/plugins/ — “Enable or disable a
  plugin for a repo” explicitly names CLI and Codex in desktop; “Bundled MCP
  servers and lifecycle hooks” establishes hook discovery and execution-environment
  requirements. Desktop plugin availability alone is not runtime test evidence.
- D3: https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/extensions.md
  — session_start, before_agent_start, session replacement and context mechanisms.
- D4: `/Users/ct/dev/projects/skein-src/devflow/specs/cli.md:45` and
  `/Users/ct/dev/projects/skein-src/devflow/specs/cli.md:56` — workspace/cwd precedence.

Planning checkout baseline: Harnesses 99d9a304b02aca002e18769327c0f8b233ee4d97;
agents b4690451e76e399398b81678add3a4d9d1cdd139. Source may advance before execution;
re-read entry points and live Strand discovery rather than treating line numbers
or this plan's proposed commands as immutable API.

# Harnesses spool

`ct.spools/harnesses` is one spool root containing the provider-neutral harness
runtime, the tracked-agent CLI, process custody, and the Claude, Codex, Cursor,
and Pi providers.

## Native identity plugins

This repository owns the focused Millstrand identity data package for Codex and
Pi under [`plugins/millstrand-identity`](plugins/millstrand-identity/README.md).
The package resolves native session bindings, publishes Pi lifecycle data,
propagates parent identity/workspace data to children, and implements the
optional managed-guidance protocol. It deliberately does not own consumer UI:
prompt owners, statuslines, and other extensions decide how to display the
published identity.

Install this checkout as a Pi package to load the standalone data extension:

```text
pi install /absolute/path/to/harnesses.spool
```

Or install from GitHub:

```text
pi install git:github.com/codethread/harnesses.spool
```

A larger Pi package may instead depend on `@codethread/harnesses` and compose
`createMillstrandIdentityLifecycle` into its own entrypoint. Public imports are
available from `@codethread/harnesses/pi/millstrand-identity`.

Add this checkout as a local Codex marketplace to install the native startup
hooks:

```text
codex plugin marketplace add /absolute/path/to/harnesses.spool
```

The marketplace exposes the `millstrand-identity` plugin package. Enable
`millstrand-identity@harnesses`; the plugin owns only `SessionStart` and
`SubagentStart`, and dialogue capture and other harness UI remain separate.

Run `pnpm check:plugins` for Pi unit/preflight checks, Codex 0.154.0 CLI
conformance, and formatting. Installing these adapters does not activate the
managed `native-v1` transport while Harnesses' production capability allowlist
remains empty.

## Activation model

Provider namespaces expose inert Millstrand declarations. Requiring one makes
its Vars available but publishes nothing. A consumer selects declarations with
the matching `use-*!` form in its own module.

Activate the complete surface with the bundled selector:

```clojure
(runtime/module! runtime :millhouse/spools-identity
  {:ns 'millhouse.spools.identity
   :required? true})
(runtime/module! runtime :harnesses
  {:ns 'ct.spools.harnesses.spool
   :after [:millhouse/spools-identity]
   :required? true})
```

This publishes:

- the `agent` operation;
- the `agent` bin;
- the headless-run event handler;
- named `agent-run-*` wait and inspection queries;
- the core, provider, and execution resources;
- process-custody reconciliation;
- durable hourly interactive-orphan reconciliation.

Loading `ct.spools.harnesses`, a provider namespace, or one of the execution
namespaces alone does not publish those declarations.

### Repository automatic delivery

The dogfood workspace registers repository-owned `auto-full-land` and
`auto-human-review` workflows with a bounded dispatcher. Pending,
dependency-ready feature cards opt in with the `auto-run` label. The default
assignment remains Sol with high effort and `auto-full-land`, with at most two
admitted workers. A card may override `auto-run/seat`, `auto-run/effort`, or
`auto-run/workflow`; only those two workflows are accepted.

Both workflows separate implementation, branch publication, repository checks,
and CI. After committing, the implementation worker pushes its branch before
repository quality runs, so quality validates the same published HEAD that
reaches review. Autonomous delivery keeps the feature claimed; only the human
review route moves it to `in_review`.

The repository-owned `.millstrand/published-candidate.sh BRANCH` runs from the
feature worktree root. It checks a clean, unchanged branch/HEAD against a fresh
fetch of the exact origin branch both before and after executing the committed,
executable `.millstrand/land-quality.sh` contract. There is no upstream-cache
fallback. The quality contract retains sole ownership of the shared suite lock
and runs `git diff --check` and `make check`. Only verified success publishes the
exact HEAD to Git's `millstrand-land-quality-head` receipt; every attempt first
invalidates the old receipt. PR preparation reads that receipt explicitly.
This candidate contract does not replace shared Land's main/merge checks.

`auto-full-land` calls Codethread's shared autonomous Land policy, which creates
a distinct finisher target whose worker owns sign-off, FIFO merge, cleanup, and
final card closure. The implementation worker cannot sign off its own change.
Both roles use Codethread's canonical
[agent blocker contract](https://github.com/codethread/codethread.spool/blob/main/docs/processes/auto-run.md#agent-blocker-contract);
Harnesses adds only the full-land custody boundaries.

`auto-human-review` instead stops at a human checkpoint after preparing a
passing, non-draft PR for the exact tested head. It leaves the feature, branch,
and worktree intact. The worker must not choose the checkpoint, start Land,
merge, close the feature, or launch a finisher.

Inspect configuration and durable one-shot receipts with:

```text
strand auto-run status
strand workflow show auto-full-land
strand workflow show auto-human-review
```

A preparation or admission error is retained on the card and is not retried
automatically. A normal runtime source refresh reconciles the checked-in policy
with an already running dispatcher; it does not require a restart or manual
configuration call. Workspace activation and same-runtime policy refresh are
covered in a disposable Weaver world by running `clojure -M:auto-run-test` from
`.millstrand`.

## Shared Codethread catalog

Codethread consumers can use the shared configuration spool to activate the
shared agent surface, including provider resources, aliases, and reviewers.
The bootstrap deliberately leaves the asynchronous Workflow `:agent` executor
for the consumer's final registration step, after any consumer workflows:

```clojure
(require '[ct.spools.codethread.bootstrap :as codethread])
(codethread/register! runtime)

;; Register consumer aliases and workflow modules here.
(codethread/register-executor! runtime [:consumer/workflows])
```

The bootstrap owns shared module ordering and the reusable catalog. Its
preferred role aliases are `luna`, `oracle`, `grunt`, `reviewer`, and
`coordinator`; effort-specific compatibility seats remain available. Claude
and Cursor are declared but disabled by default, matching the authoritative
Harnesses workspace policy. Consumers can enable either provider with the
process-local agent configuration command when needed.

`register-executor!` owns the sole `:agent` executor. Pass the consumer module
ids whose resources or workflows must reconcile before its initial ready-gate
scan. Consumers must not activate
`ct.spools.harnesses.executors.agent.spool` directly or register a second
provider catalog.

The Harnesses dogfood workspace keeps its own checkout as a local
`ct.spools/harnesses` root and pins `codethread/config`, Devflow, and the
Devflow Kanban adapter in [`.millstrand/deps.edn`](.millstrand/deps.edn).
Published consumers should pin the shared Harnesses and config dependencies.
They should not copy the provider, alias, reviewer, query, or executor roster
into their own workspace modules.

A standalone consumer first supplies the source dependency, then activates the
modules. The dependency makes the namespace loadable; `runtime/module!` is the
activation step. This local checkout example keeps a consumer workflow module
before the shared executor:

```clojure
;; consumer deps.edn, with both checkouts side by side
{:deps {ct.spools/harnesses {:local/root "../harnesses.spool"}
        codethread/config
        {:git/url "https://github.com/codethread/codethread.spool.git"
         :git/sha "dc25ea43f7a271a435d62daf855a202d69a62b0c"
         :deps/root "spools/config"}}}

;; consumer .millstrand/init.clj
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime]
         '[ct.spools.codethread.bootstrap :as codethread])

(let [runtime (current/runtime)]
  (codethread/register! runtime)
  (runtime/module! runtime :consumer/workflows
                   {:ns 'consumer.workflows
                    :after [:millhouse/spools-workflow]
                    :required? true})
  (codethread/register-executor! runtime [:consumer/workflows]))
```

## Select declarations

Consumers can import any declaration and select it explicitly:

```clojure
(ns app.harnesses
  (:require [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.agent-bin :as agent-bin]
            [ct.spools.harnesses.agent-cli :as agent-cli]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.process-custody :as process-custody]
            [ct.spools.harnesses.reconciliation :as reconciliation]
            [ct.spools.harnesses.providers.claude :as claude]
            [ct.spools.harnesses.providers.codex :as codex]
            [ct.spools.harnesses.providers.cursor :as cursor]
            [ct.spools.harnesses.providers.pi :as pi]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-op! agent-cli/agent)
(millstrand/use-handler! execution/on-event)
(millstrand/use-bin! agent-bin/agent)

(lifecycle/use-resource!
 harnesses/harness-core-runtime
 claude/claude-harness-runtime
 codex/codex-harness-runtime
 cursor/cursor-harness-runtime
 pi/pi-harness-runtime
 execution/harness-execution-runtime)

(lifecycle/use-reconcile!
 process-custody/harness-process-custody
 reconciliation/interactive-reconciliation-sweep)
```

The execution resource starts after all four provider resources. Select the full
resource set when publishing asynchronous execution. Provider resources may be
selected independently with the core resource when only registration and the
Clojure API are required.

## Millhouse Workflow adapter

Workflow support is optional and separately activated. The shared Codethread
bootstrap owns the Workflow engine and Harnesses surface. Consumers that need
the workflow CLI/providers activate `millhouse.spools.workflow.spool` after the
shared bootstrap, then register the shared executor last:

```clojure
(require '[ct.spools.codethread.bootstrap :as codethread])
(codethread/register! runtime)
(runtime/module! runtime :millhouse/spools-workflow-all
  {:ns 'millhouse.spools.workflow.spool
   :after [:millhouse/spools-workflow]
   :required? true})
(codethread/register-executor! runtime [:millhouse/spools-workflow-all])
```

Place modules that register harness aliases and workflows before
`register-executor!`. Its resource performs an initial scan, so every alias
named by a durable ready gate must already resolve.

The workflow selector publishes the workflow CLI/providers and their ordinary
executor resources. The shared executor registration publishes the Workflow
`:agent` executor and its event resource without changing the Harnesses engine.

Use waiter `:agent` for a gate fulfilled by a headless tracked run:

```clojure
(workflow/gate :review
               "Review the change"
               :agent
               :attributes
               {"harness/alias" "reviewer"
                "harness/prompt" "Review the current diff and report findings."
                "harness/cwd" "/path/to/worktree"
                "harness/effort" "high"})
```

`harness/alias` is required. `harness/prompt` falls back to
`workflow/instruction`, `description`, then the gate title. `harness/cwd` is
optional. Portable overlays (`harness/model`, `harness/effort`,
`harness/extra-argv`, and `harness/appended-system-prompts`) and provider
overlays such as `harness.pi/*` pass through to the run. The gate instruction
remains the main prompt; workflow context and completion guidance are appended
to the system prompt after any supplied system prompts. Interactive mode is not
supported because it has no automatic workflow-completion contract.

Before creating a run, the adapter records `agent-executor/spawn-attempt` and a
private `agent-executor/spawn-session-id` claim on the gate. It then creates the
run through the unchanged Harnesses API and adds `workflow/run-id` plus a
`serves` edge itself. After a Weaver interruption, the next scan adopts an
unlinked run carrying the claimed session ID or resumes creation at the next
attempt. Three unsuccessful attempts stamp `gate/error`; a successful link
removes the private session claim and retains the attempt count for audit.

A successful non-blank `harness/result` closes the gate through the Workflow
`run-complete!` executor boundary. The gate records `workflow/executor=agent`
and the Harness run ID in `workflow/executor-run-id`; it does not mislabel that
opaque run ID as a domain actor. The result is copied onto the gate. A failed
run remains active and stalls the gate; retry it
with `strand agent retry <run-id>`. `stalled-agent-gates` reports failed runs and
gates carrying `gate/error`. After fixing a spawn request, remove `gate/error`
to start a fresh bounded attempt series.

## Development

Follow the shared [Clojure lint and editor configuration](https://github.com/codethread/codethread.spool/blob/main/docs/processes/kondo-and-lsp.md) when refreshing static-analysis configuration.

## Providers

The core owns the shared `harness/model`, `harness/effort`, and
`harness/extra-argv` overlay attributes. Providers read the same strand fields
and materialize them with their native CLI flags: Claude uses `--effort`, Codex
uses `model_reasoning_effort`, and Cursor and Pi use `--thinking`.

Register aliases with a documented descriptor and optional top-level `:model`,
`:effort`, and `:append-system-prompt`. Parent and child appended system prompts
accumulate in that order, while model and effort values are replaced by the
nearest child. Effort is intentionally open rather than restricted to a fixed
set, so provider integrations may pass it through or remap it before building
the command. Codex currently maps `low` to its native `light`; other values pass
through unchanged.

```clojure
(harnesses/register-alias!
 runtime :terra
 {:doc "Use the Terra model with medium effort."
  :parent :pi
  :model "openai-codex/gpt-5.6-terra"
  :effort :medium
  :append-system-prompt "Act as a read-only reviewer."
  :attributes {}})
```

Aliases may name another alias as their parent. Child model and effort values
replace their parent values. Register a vector of complete descriptors to define
ordered fallbacks. Each descriptor may use a flag expression with `:when`:

```clojure
(harnesses/register-alias!
 runtime :oracle
 [{:doc "Use Fable."
   :parent :fable
   :when [:and :seat/fable [:not :seat/maintenance]]
   :effort :high
   :attributes {}}
  {:doc "Fall back to Sol."
   :parent :sol
   :effort :max
   :attributes {}}])
```

Conditions support a flag name and the `:and`, `:or`, and `:not` operators.
Unset custom flags are false. Concrete harnesses start enabled under their
`harness/<name>` flag and remain registered when disabled.

A caller can add run-specific guidance without changing the alias:

```text
strand agent run reviewer --prompt "Review this change" \
  --append-system-prompt "Focus on concurrency risks."
```

Claude and Pi receive one native append flag per ordinary guidance contribution.
Codex receives accumulated ordinary contributions joined with blank lines; its
identity comes from the native hook. Cursor receives the identity plus appends.
System-prompt injection is rebuilt from frozen run data for every launch,
including native resume.

Runtime flags are intentionally process-local:

```text
strand agent config list
strand agent config set harness/claude false
strand agent config unset seat/fable
```

Use `strand agent list` to inspect available provider harnesses and aliases
with their selected resolution and effective model and thinking level. Pass
`--full` for the complete visible registry, including unavailable entries and
their reasons.

Mutating agent commands accept `--by-identity` to name the operation actor. The
nonblank friendly string is durable even when it is unknown or ambiguous in the
local Identity registry; those states never reject an otherwise-valid mutation.
`list` uses a uniquely resolved caller's latest performed run to apply that
caller's alias visibility policy:

```clojure
{:doc "Reviewer seat."
 :parent :pi
 :allow #{:reviewer :oracle}
 :attributes {}}

{:doc "Restricted seat."
 :parent :pi
 :deny #{:luna :codex}
 :attributes {}}
```

`:allow` and `:deny` are mutually exclusive sets of harness or alias names.
Each name includes aliases that currently resolve through it, so hiding
`:fable` also hides an `:oracle` currently using `:fable` as its parent.

```text
strand agent list --by-identity gentle-cool-puma
```

An unresolved or ambiguous `list --by-identity` returns an empty listing rather
than silently falling back to the unfiltered registry. `show`, `runs`,
`resumable`, and `reviewers` accept caller context without writing mutation
history.

`run`, `assign`, `review`, and `resume` store `identity/by-identity` on each
created run. `stop`, `retry`, explicit abandonment, and `self-complete` append an
immutable action note carrying the same canonical attribute. Identity
reconciliation projects an exact unique local match as
`identity --attributed--> source`. Worker identity remains `identity/id` on the
run and `worker identity --performed--> run`; native session attachment and
explicit `parent-of` are separate strict Identity-spool contracts. Consumers
recover one delegation participation by joining `attributed -> run <- performed`
and recover retries from every `performed` edge, not only the run's current
mutable `identity/id`.

Agents pass their `MILLSTRAND_AGENT_ID` explicitly at the Strand client
boundary; Weaver never reads a caller's environment. The user-only agent bin
does not supply agent identity.

Use `mill bin run agent <agent> [wrapper options] -- <provider args>` to launch
an interactive tracked session. The first literal `--` ends wrapper options;
every later shell argument is appended to `harness/extra-argv` in its original
order, including dash-prefixed values and quoted values containing spaces.
Values such as `:stdin`, `:payload/example`, `{{RUN_ID}}`, and `{{AGENT_ID}}`
remain literal provider arguments rather than payload or invocation templates.
Caller arguments use the ordinary Harnesses overlay precedence, so they replace
an alias or provider's generated `harness/extra-argv` value.

For example:

```text
mill bin run agent pi --thinking high -- --provider-flag "one value" --debug
```

The bin carries provider arguments through repeated internal `--extra-argv`
values rather than encoding shell argv as JSON. The created run remains the
same tracked interactive lifecycle driven by `agent run --interactive`, its
private launcher, `_started`, and `_finished`.

## Native identity and run registration (Codex and Pi)

Codex and Pi create no identity before launch. Their ordinary task, role, frozen
policy, and ordered alias/user appends stay on the launch prompt and
developer-instruction paths. Only the canonical identity contribution comes from
native startup. Claude and Cursor retain their maintenance transports.

The packaged hooks gate on the launch project's canonical `.millstrand`
workspace, including Git linked worktrees. Outside such a project they do
nothing—even if workspace or managed hints were inherited. They never start a
Weaver, create a workspace, or use a global fallback. Enable/trust the packaged
hooks in the host before using this integration.

Codex managed roots carry only `MILLSTRAND_RUN_REFERENCE=RUN_ID:INVOCATION`.
Pi managed correlation is only `MILLSTRAND_RUN_ID`, and there is no Pi
reservation, identity-bearing bootstrap, generated identity append, or
guidance-transport selection. Task, policy and ordered alias/user guidance stay
on ordinary prompt paths. Startup failure is visible; a process exit cannot
invent attachment. Same-session resume retains identity but attaches each new
managed invocation.

The small registration boundary is:

```text
strand --workspace PROJECT_WORKSPACE --cwd SESSION_CWD \
  agent native-startup HARNESS ACTUAL_NATIVE_ID --model ACTUAL_MODEL \
  [--run-reference RUN_ID:INVOCATION] [--run-id MANAGED_RUN_ID] \
  [--parent-identity FRIENDLY] [--parent-native-session-id HOST_HEADER_ID]
```

`ct.spools.harnesses/register-native-session!` dispatches on the provider and
composes Millhouse Identity startup. Codex uses
`ct.spools.harnesses.native-session/register!`, which fences the exact managed
invocation through `--run-reference RUN_ID:INVOCATION`; the hook awaits the
result, validates the canonical instruction, and returns developer
`additionalContext` before model work. Pi uses
`ct.spools.harnesses.internal.native-registration/register!`, where `--run-id`
must match the pinned native session, current running attempt/invocation,
provider and cwd, and a native fork header supplies `--parent-native-session-id`
with parent attribution. Startup, resume, clear, and compact each reconstruct one
current contribution. Codex SubagentStart uses
`codex-child:v1:<base64url(parent)>:<base64url(agent)>`, resolves parent identity
separately, and never consumes inherited root run correlation.

### Shared persistence contract

- Managed launch correlation is provider-specific: Codex passes
  `MILLSTRAND_RUN_REFERENCE=RUN_ID:INVOCATION`; Pi passes `MILLSTRAND_RUN_ID` for
  its pinned session. `MILLSTRAND_RUN_ID` remains ordinary run metadata, not
  identity authority.
- There is no Codex or Pi identity reservation, identity environment variable,
  rich bootstrap document, transport selection, completion-time mint, or legacy
  repair. Pi's former reservation/guidance APIs are removed.
- Publication commits request/target tracking without a worker identity.
  Native registration validates the running invocation, provider, canonical cwd,
  expected resume session and competing writers. It sets `identity/id`, the actual
  `harness/session-id`, and the identity's `performed` edge to that exact run.
- Attachment retains `harness/native-attached=true`, `native-attached-at`,
  `native-attachment-source=native-startup`, and managed
  `native-attachment-attempt`/`native-attachment-invocation`. Provenance and the run
  patch commit together. Identity lookup/mint is independently idempotent.
- Without a managed reference, registration creates/reuses one run per
  provider/native session. It has `harness/mode=external` and
  `harness/ownership=external`, no alias, target, attempt, invocation, launch
  custody or settlement assertion. It does not spawn an agent or claim work.
  Harnesses refuses process stop/completion for these externally owned sessions.
- `harness/observed-model` records the actual host model. Codex hooks do not
  report reasoning effort: `harness/observed-effort` is the literal **`unknown`**
  unless an authoritative managed selection supplies it. Consumers display
  **Unknown**. This metadata is separate from `harness/effort`, the provider
  option; the unknown marker is never passed as a reasoning-effort setting.
- Alias visibility policy is unchanged. An external run without an alias does
  not acquire unrestricted delegation visibility.

Same-session callbacks recover the identity and direct registration. Managed
resume keeps its true lineage but waits for its own native callback to record
participation. Fresh retries/children get fresh native identities. Request replay
and target exclusivity are independent of registration. A missing callback or
inconsistent observed thread produces a visible bootstrap failure at completion,
without discarding genuine process settlement. Registration is not custody or
proof that a model obeyed its context. Missing/disabled hooks cannot themselves
block a host; completion never pretends that such a run integrated successfully.

Source delivery does not install plugins, update consumers, or restart a runtime.
Combined provider acceptance and package/runtime rollout are downstream.

- Managed launch correlation is only `MILLSTRAND_RUN_REFERENCE=RUN_ID:INVOCATION`.
  `MILLSTRAND_RUN_ID` remains ordinary run metadata, not identity authority.
  There is no Codex identity reservation, identity environment variable, rich
  bootstrap document, transport selection, completion-time mint, or legacy repair.
- Publication commits request/target tracking without a worker identity.
  Native registration validates the running invocation, provider, canonical cwd,
  expected resume session and competing writers. It sets `identity/id`, the actual
  `harness/session-id`, and the identity's `performed` edge to that exact run.
- Attachment retains `harness/native-attached=true`, `native-attached-at`,
  `native-attachment-source=native-startup`, and managed
  `native-attachment-attempt`/`native-attachment-invocation`. Provenance and the run
  patch commit together. Identity lookup/mint is independently idempotent.
- Without a managed reference, registration creates/reuses one run per
  provider/native session. It has `harness/mode=external` and
  `harness/ownership=external`, no alias, target, attempt, invocation, launch
  custody or settlement assertion. It does not spawn an agent or claim work.
  Harnesses refuses process stop/completion for these externally owned sessions.
- `harness/observed-model` records the actual host model. Codex hooks do not
  report reasoning effort: `harness/observed-effort` is the literal **`unknown`**
  unless an authoritative managed selection supplies it. Consumers display
  **Unknown**. This metadata is separate from `harness/effort`, the provider
  option; the unknown marker is never passed as a reasoning-effort setting.
- Alias visibility policy is unchanged. An external run without an alias does
  not acquire unrestricted delegation visibility.

Same-session callbacks recover the identity and direct registration. Managed
resume keeps its true lineage but waits for its own native callback to record
participation. Fresh retries/children get fresh native identities. Request replay
and target exclusivity are independent of registration. A missing callback or
inconsistent observed thread produces a visible bootstrap failure at completion,
without discarding genuine process settlement. Registration is not custody or
proof that a model obeyed its context. Missing/disabled hooks cannot themselves
block a host; completion never pretends that such a run integrated successfully.

Pi's prior reservation/guidance APIs remain provider-scoped until its independent
cutover lands; Codex rejects their transport selection and does not use them.
Source delivery does not install plugins, update consumers, or restart a runtime.
Combined provider acceptance and package/runtime rollout are downstream.

### Reconcile orphaned interactive runs

A launcher that is killed before `_finished` can leave its run active. Inspect
one run, or all active interactive runs, without changing state:

```text
strand agent reconcile <run-id> --dry-run
strand agent reconcile --dry-run
```

Ordinary reconciliation abandons only when both the completion-owning bin and
actual provider exec have recorded PID/process-start fences proving those exact
local processes are gone or replaced. A matching live or idle process, a live
completion owner, a native process naming the session, a newer active session
writer, remote evidence, and unavailable evidence are preserved. The result is
explicitly `stopped/abandoned` with `settled=false`; it records no exit code,
retains target and session reservations, and cannot authorize native resume.

Legacy runs predate launcher custody evidence and therefore remain unknown.
After external inspection, an operator can attest abandonment with an exact run
ID and durable reason:

```text
strand agent reconcile <run-id> --abandon \
  --reason "launcher ownership was lost" --by-identity <operator-identity>
```

The bundled selector schedules the same safe reconciliation through
Millstrand's durable scheduler every 60 minutes. Set
`MILLSTRAND_HARNESS_RECONCILIATION_INTERVAL_MS` to a positive integer before
starting Weaver to choose another cadence, or to the exact value `disabled` to
disable it. Each fire inspects at most 100 runs and persists a rotating offset
in the next wake so ambiguous early rows cannot starve later candidates. Bulk
manual results expose the same `next-offset` cursor, accepted by a subsequent
`--offset` scan. Normal runtime restarts preserve the existing durable deadline.
Cadence is never process-death evidence, and the sweep never stops or restarts
Mill.

`bin/agent` is the mutable client entrypoint, while launcher scripts and agent
callback grammar come from the Harnesses library loaded by a particular
Weaver. The bin queries that backend's callback contract before sending new
PID/invocation fences. A pre-upgrade backend therefore receives its legacy
callbacks, and an upgraded backend continues to accept callbacks from already
running legacy bins and launchers. Legacy callbacks remain completable but lack
retrospective process-custody evidence, so ordinary reconciliation keeps them
unknown.

Updating this checkout does not update an already loaded Weaver. The callback
contract, provider custody, and scheduled sweep take effect only after the
supported runtime module update has loaded this library; source tests do not
constitute deployment evidence.

## Declarative reviewers

The Harnesses reviewer API provides small, read-only lenses for repository changes. A declaration belongs in a workspace module, so a repository can publish useful review policy without copying a large roster from another project.

Authoring and activation are separate. `defreviewer` defines an inert declaration; `use-reviewer!` selects one or more declarations in the active module. `defreviewer!` is the shorthand that defines and selects one declaration. The kind provider must be selected before reviewer entries are selected; a module file that contains repository policy should run after that provider module.

```clojure
(ns me.reviewers
  (:require [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]))

(reviewers/defreviewer
 docs-and-tests
 "Check contract coverage in docs and tests."
 {:seat ['luna 'reviewer]
  :labels ["PR" "Docs" "Tests"]
  :glob ["README.md" "docs/**" "src/**" "test/**"]}
 (format-alpha/prose
  "
    Check changed docs, source, and tests against the promised contract.
    Report actionable P1/P2 findings with paths and lines, or `No findings`.
    Do not edit files."
  {}))

(reviewers/use-reviewer! docs-and-tests)
```

The required `:seat` names a registered alias, as a symbol or keyword, or an ordered vector such as `['reviewer 'luna]`. The first currently available alias is chosen before spawning; this is availability fallback, not a retry or a new provider-selection engine. `:labels` and `:glob` are optional. The final argument is an evaluated prompt expression, so `format-alpha/prose` is suitable for readable multi-paragraph policy. An optional `:system-prompt` is appended after the selected alias guidance.

The shared Codethread config publishes the common repository lenses used by
this workspace. Consumers can add their own declarations in a module after the
shared bootstrap; a dependency coordinate only makes a namespace available and
does not activate it. Activation is the module's typed `use-reviewer!`
selection, and any agent or task coordination is a separate concern.

Discover the declarations and the command guidance with:

```text
strand agent reviewers
strand help agent review
strand prime agent
```

`strand agent review` repeats `--agent` to select reviewer declaration names with OR semantics. Explicit names override those reviewers' globs. Repeat `--label` for OR label matching; names and labels together intersect. Without explicit names, a reviewer applies when any of its globs matches any changed path. A reviewer with no globs applies unconditionally to a nonempty diff. Selection is deterministic, and unknown names or labels fail before fan-out.

The default review surface uses the selected base and includes branch commits plus staged, unstaged, and non-ignored untracked changes in the current tree. `--branch <ref>` reviews the committed merge-base-to-ref range only; it does not check out the ref and does not include the current tree's dirty changes. Use `--base <ref>` to choose the base explicitly. Removed and renamed paths remain part of selection.

`--git` is literal unified-diff content, not a shell command. It accepts declared text payloads such as `:stdin` or `:payload/diff`. Captured diffs are bounded to 512 KiB by default; use `--max-bytes` to choose another positive bound. An oversized diff is an error with a remedy, never a silently truncated review. Empty input produces a structured no-changes skip rather than a fake successful run.

Review runs are asynchronous and return durable run IDs. Inspect each result with `strand agent show <run-id>`. Wait for positive evidence with a named query and a positive minimum count; `agent-run-terminal` proves a terminal state, while `agent-run-settled` also proves the provider process is gone:

```text
strand await --query agent-run-settled --param run-id=<run-id> --min-count 1
strand agent show <run-id>
```

For a literal patch piped from Git, this Nushell example keeps the patch as data. Strand returns the command result as JSON, so no JSON flag is needed:

```nu
git diff | strand --stdin agent review --git :stdin
```

When that result contains `runs` with `id` fields, parse it and await each run explicitly:

```nu
let result = (git diff | strand --stdin agent review --git :stdin | from json)
$result.runs | each {|run|
  strand await --query agent-run-settled --param $"run-id=($run.id)" --min-count 1
  strand agent show $run.id
}
```

For a saved patch, use a named payload rather than interpolating patch text into a shell command:

```nu
let result = (strand --payload diff=patch.diff agent review --git :payload/diff | from json)
$result.runs | each {|run| strand agent show $run.id }
```

The reviewer command composes the existing shell/execution boundary: it captures the diff, creates tracked headless runs, and schedules them through the existing execution machinery. It does not introduce a builtin workflow executor, force a synthesizer, or infer semantic pass/fail from freeform review text. Consumers may compose a shell executor or their own synthesis step from the returned run IDs.

## Assignment

`strand agent assign` accepts an assignment and publishes a ready headless run
serving a work target. Assignment names a worker but does not change Kanban
ownership, claim a target, create a worktree, or replace the reporter. `--cwd`
is explicit and required.

```text
strand agent assign luna --task F --cwd /worktrees/feature-f --policy NAME
```

Generated guidance reads Kanban's durable ownership history. Only an unowned,
pending feature receives a first-claim command. A worker that is already the
latest explicit owner continues without another claim; a different current
owner requires an explicit handoff/reclaim before editing. A task assignment
serves that task directly and never tells the worker to claim its parent feature
or emits a `kanban claim` command against the task. Reviewers and note authors
can participate without becoming owner.

The policy name and exact registered prose are frozen when accepted. Policy
names do not imply behavior: custom prose registered under a built-in name is
used verbatim. Frozen provider guidance carries state-independent ownership
rules, so native resume does not replay a stale first-claim assertion. A fresh
`--after` run keeps the target, policy, logical lineage, and ancestor history,
then receives guidance for ownership at its own acceptance. Process exit never
closes the target or releases its dependency chain. Codex and Pi assignment
guidance refers to the native startup identity instead of interpolating a
pre-created name. Other providers retain their current launch identity paths.

Publication receipts (`agent show` and `agent assign`) expose
`publication-phase`, `publication-outcome`, and, on interruption,
`publication-reason`. Binding publication alone is not acceptance: assignment
identity/run-id enrichment and continuation bookkeeping must commit before the
run can launch. Phases retain the last completed boundary (`created`, `bound`,
`published`, or `complete`); the outcome is `publishing`, `committed`, or
`interrupted`.

If publication fails or a cooperative operation deadline interrupts it, the
original error remains an error. An incomplete ready run with no execution
attempt settles as `stopped/requested`, `never-launched`. Its request key,
fingerprint, partial identity/reservation evidence, and history are retained.
Exact replay returns that same interruption, never re-enriches it or creates
another worker. A fully committed run remains accepted even if its response
was lost. Readback and execution startup recognize retained incomplete rows;
possible execution custody never acquires invented settlement evidence.

Consumers that must check accepted lineage and record a related receipt can use
`ct.spools.harnesses/call-with-run-publication-lock` with `[runtime thunk]`.
It calls the zero-argument thunk synchronously under the same runtime-local
monitor as run creation and continuation publication. Same-thread calls are
reentrant, including calls to Harnesses publication APIs. The return value or
exception passes through unchanged, and the monitor is released on either exit.
Keep this section bounded: do not wait on external work or another thread that
may need the monitor. It provides no database transaction, rollback, protection
against raw edits, or protection for asynchronous work after the thunk returns.

An interrupted child is not an accepted continuation head. Continue only by an
explicit normal request from a valid accepted predecessor, or by a separately
authorized fresh assignment. Publication recovery does not retry work, attach
native sessions, or grant that authorization. These semantics require loading
the updated source through the consumer's supported activation path.

Blocked targets are accepted but remain queued until their `depends-on`
blockers close. Independent targets launch concurrently, and scheduling
rechecks target readiness. Parallel workers use distinct task targets; each run
keeps its direct `serves` edge and every ancestor `serves-root` edge. Wait with
positive-evidence queries:

```text
strand await --query agent-run-terminal --param run-id=<id> --min-count 1
strand await --query agent-run-settled --param run-id=<id> --min-count 1
strand await --query agent-work-complete --param target=<feature-id> --min-count 1
strand await --query agent-work-complete-or-intervention \\
  --param target=<feature-id> --min-count 1
```

Work queries require positive closed-card or failed-run evidence. An empty
active set, a stopped run, and a resumed predecessor are not completion
evidence. After stopping, await settlement, update the feature and tasks with
a primer that explicitly supersedes old instructions, then resume explicitly
against that primer. Native resume preserves the concrete provider, native
session, target, settings, and frozen guidance; `--after` is the explicit fresh
continuation and never an implicit fallback. An exact `resume --request-id`
replay returns its originally accepted child before resolving a newer lineage
head; changed selectors or payload fail under the immutable request fingerprint.
A run's reported local resume eligibility does not prove it is the accepted
lineage head, because an already published child makes the predecessor stale.

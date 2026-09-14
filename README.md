# Harnesses spool

`ct.spools/harnesses` is one spool root containing the provider-neutral harness
runtime, the tracked-agent CLI, process custody, and the Claude, Codex, Cursor,
and Pi providers.

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
         :git/sha "252eeaee216a5e4d4e82c6b2948dd9eba1dafc9d"
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

A successful non-blank `harness/result` closes the gate through
`workflow/complete!`, records the run ID in `workflow/outcome-by`, and copies the
result onto the gate. A failed run remains active and stalls the gate; retry it
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

Claude and Pi receive one native append flag per contribution. Codex and Cursor
receive the identity and accumulated contributions joined with blank lines.
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

Identity-bearing agent commands accept `--by-identity` to name the agent
performing the operation. `list` applies the caller alias's visibility policy:

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

On `run` and `resume`, the caller identity gains a `parent-of` edge to the
spawned session identity. Agents pass their `MILLSTRAND_AGENT_ID` explicitly at
the Strand client boundary; Weaver never reads a caller's environment. The
user-only agent bin does not supply agent identity.

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

## Managed Codex/Pi native startup

Managed Codex and Pi runs reserve identity before run publication. This is an
optional compatibility adapter: unmanaged desktop startup continues to use the
identity spool directly and requires no Harnesses run, launcher environment, or
reservation. Claude and Cursor retain their existing binding and prompt paths.

Every running managed root receives `MILLSTRAND_MANAGED_BOOTSTRAP` as JSON. The
document contains routing and fencing metadata only—never the user prompt,
identity instruction, assignment policy, or appended guidance. Its exact v1
shape is:

```json
{
  "schema": "millstrand.agent-managed-bootstrap/v1",
  "run-id": "abc12",
  "harness": "codex",
  "identity": "warm-silver-lemur",
  "reservation-id": "907302db-f1a1-4f20-9908-da397415a7c8",
  "cwd": "/canonical/session/cwd",
  "workspace": "/canonical/workspace",
  "attempt": 1,
  "invocation": "fencing-uuid",
  "scope": "root"
}
```

`expected-native-session-id` is additionally present for Pi, whose pinned
session ID must equal the host's actual ID, and for an already attached Codex
native resume. The adapter passes the complete document unchanged and supplies
the host event's actual harness, native session ID, cwd, and scope:

```text
strand --workspace WORKSPACE --cwd SESSION_CWD \
  agent startup HARNESS ACTUAL_NATIVE_ID \
  --scope root --bootstrap "$MILLSTRAND_MANAGED_BOOTSTRAP"
```

Startup validates the published run, concrete Codex/Pi provider, canonical cwd
and workspace, root scope, positive durable attempt, nonblank durable invocation,
reservation, friendly identity, immutable prior attachment, target writer, and
native-session writer. Pi additionally requires the bootstrap pin and compares
both it and the actual host ID independently with the durable provisional ID.
Identity binding, `performed`/`parent-of` provenance, and run attachment evidence
then commit in one transaction. Exact replay converges. Never-launched runs,
child scope, stale launch metadata, and conflicts fail without attachment writes.

A successful startup response has this exact context structure (normal Strand
output also adds its `operation` key):

```json
{
  "schema": "millstrand.agent-managed-context/v1",
  "run-id": "abc12",
  "harness": "codex",
  "native-session-id": "actual-thread-id",
  "identity": "warm-silver-lemur",
  "strand-id": "identity-strand-id",
  "result": "attached",
  "instruction": "Your Millstrand identity is …",
  "context": {
    "schema": "millstrand.agent-managed-context/v1",
    "identity-instruction": "Your Millstrand identity is …",
    "appended-system-prompts": ["ordered frozen contribution"]
  }
}
```

The context omits the main user task. Identity instruction comes first;
`appended-system-prompts` retains parent-to-child-to-run ordering. The Codex/Pi
native adapters own how this structured result is delivered. Existing CLI prompt
flags remain active until an explicit native transport version selects their
replacement.

### Managed guidance transport

New managed Codex/Pi requests accept an explicit delivery selection:

```text
strand agent run pi --guidance-transport legacy ...
strand agent resume RUN_ID --guidance-transport legacy ...
strand agent retry RUN_ID --guidance-transport legacy
```

The values are exactly `legacy` and `native-v1`. Fresh work defaults to `legacy`;
a continuation inherits its predecessor's selection. The exact choice, frozen
context template, materialized current-run context, RFC 8785 bundle digest, and
ordered attempt records are durable. Intentionally equal appended strings remain
separate vector positions. Older rows with no guidance attributes remain legacy;
a partial versioned representation is corruption rather than a downgrade signal.

`native-v1` is deliberately unavailable in this release. Harnesses' production
capability allowlist is empty until the Agents adapter artifacts and exact Codex
0.154.0/Pi 0.84.4 host profiles are independently accepted. An explicit native
request therefore fails before run publication or identity reservation with the
remedy to submit legacy work. Harnesses never infers capability from installed
files, startup-v1 metadata, package versions, branches, or helper claims.

When profiles are eventually accepted, Harnesses runs the approved no-model
preflight against the actual executable, cwd, workspace, environment, provider
selectors, and resume settings before publication and again before each attempt.
The canonical executable path remains an explicit field in the bounded private
request.
Evidence must match one approved preflight source and the complete approved
adapter/executable/package/profile closure. Missing, changed, untrusted,
duplicate, malformed, oversized, nonzero, or mismatched evidence fails loudly;
there is no retry or native-to-legacy fallback.

A selected native launch exports both prompt-free routing documents:

```text
MILLSTRAND_MANAGED_BOOTSTRAP
MILLSTRAND_MANAGED_GUIDANCE
```

The native guidance document fences run ID, positive attempt, invocation,
provider, bundle digest, and capability digest. `agent startup` additionally
accepts `--guidance` and returns the frozen
`millstrand.agent-guidance-bundle/v1` only after the actual native root session
passes every attachment fence. The digest is:

```text
SHA256(UTF8(RFC8785([run-id, canonical-workspace, context])))
```

The adapter renders identity first, then every ordered append, then the
current-run/workspace footer. It records adapter handoff with `agent guidance
acknowledge --receipt JSON`, or failure with `agent guidance fail --receipt
JSON`. Attempt states are `pending`, `fetched`, `acknowledged`, and `failed`;
legacy attempts are `not-required`. Exact receipt replay is no-write, stale
receipts cannot satisfy a newer attempt, and an unacknowledged native process
exit is a bootstrap failure. A later exact-current reconstruction failure may
move an acknowledged attempt to failed without discarding its real attachment.
Durable handoff deadlines are restored after execution reopens; obsolete timers
cannot fail retries or completed work, and fetched interactive Pi remains exempt.
Acknowledgement proves adapter handoff only—not atomic host ingestion, model
obedience, or removal of historical transcript instructions.

Native mode removes only Harnesses-generated Codex `developer_instructions` or
Pi `--append-system-prompt` arguments. Competing raw provider prompt controls are
rejected before publication; wrapper-level `--append-system-prompt` remains the
supported input. Main task prompts, model/effort, native session/resume, aliases,
and unrelated provider argv are preserved. Verified failures before launch may
settle without inventing an attempt or custody, while unknown custody remains
unsettled. Pre-reservation Pi continuations and retries remain legacy-only even
when their rows contain current guidance templates. Claude and Cursor retain
their maintenance transports unchanged.

Provider finish and late custody settlement use the same fenced attachment when
they observe usable native evidence. Hook-confirmed interactive Codex identity
survives a finish callback with no stdout. Attachment never substitutes for
process settlement, and settlement evidence remains durable when attachment
fails. Fresh retry and `--after` reserve fresh identities. Native resume and a
retry of that continuation retain the attached identity, session, cwd, provider
settings, and frozen guidance without consulting a changed or disabled alias;
incompatible retry replacements fail before writes.

A managed Codex/Pi run accepted before reservation-backed startup has no
`identity/reservation-id`, `harness/native-attached`, or
`harness/provisional-session-id`. Positive completion or usable session evidence
after a Weaver upgrade validates that the original unreserved identity belongs
to the provider and performed the run. It also requires a durable positive
attempt and an exact nonblank invocation fence before preserving that evidence.
Every positive legacy outcome—completion or usable-session evidence—must supply
its nonblank observed session ID. Pi must exactly match its durable binding;
Codex may differ but remains eligible only for explicit repair. A failed outcome with no usable session
carries no optional identity attachment; its exact-invocation custody settlement
remains recordable even if the historical identity is missing or damaged. A genuine prelaunch failure remains recordable
without an attempt. A damaged current run still has startup-v1 representation
and is rejected by the strict reservation checks; it is never treated as legacy.

A legacy Pi run may continue only when its stored session exactly matches one
unique unreserved Pi identity and the `performed` provenance is intact. A raw
continuation request must explicitly retain both the resolved Pi provider and
that exact session before any child or provenance write. Reservation-backed
Codex/Pi continuations enforce the same explicit provider/session intent before
commit. That continuation and an in-place retry retain the identity, session,
frozen settings, and legacy
launcher transport without claiming startup-v1 bootstrap or attachment evidence. A legacy Codex binding cannot prove that its provisional identity names
the observed session, so Codex native resume remains unavailable until explicit
repair. Fresh `--after` work uses a new reservation. Cancellation settlement says
only that custody is settled; it never invents successful completion.

One completed legacy Codex mismatch can be repaired only with all three recorded
values supplied explicitly:

```text
strand agent repair-startup RUN_ID \
  --identity FRIENDLY_ID --native-session-id ACTUAL_NATIVE_ID
```

Repair requires a settled, usable Codex run, matching `performed` provenance, an
unoccupied native session, and no conflicting active target/session writer. It
converts only that identity to an attached reservation, committing identity,
provenance, and run evidence atomically, and is replay-safe. There is no
discovery scan, broad migration, fallback mint, identity stealing, or automatic
repair.

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
serving a work target. The
agent claims the target itself; the bridge does not claim cards or create
worktrees. `--cwd` is explicit and required.

```text
strand agent assign luna --task F --cwd /worktrees/feature-f --policy NAME
```

The policy name and exact registered prose are frozen when accepted. Policy
names do not imply behavior: custom prose registered under a built-in name is
used verbatim. Process exit never closes the target or releases its dependency
chain. The launched process receives reserved `MILLSTRAND_AGENT_ID` and
`MILLSTRAND_RUN_ID` values, which override user environment values.

Blocked targets are accepted but remain queued until their `depends-on`
blockers close. Independent targets launch concurrently, and scheduling
rechecks target readiness. Wait with positive-evidence queries:

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
continuation and never an implicit fallback.

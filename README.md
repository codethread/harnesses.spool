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
- process-custody reconciliation.

Loading `ct.spools.harnesses`, a provider namespace, or one of the execution
namespaces alone does not publish those declarations.

A standalone consumer first supplies the source dependency, then activates the
modules. The dependency makes the namespace loadable; `runtime/module!` is the
activation step. This local checkout example is complete and keeps the reviewer
module after the bundled Harnesses selector:

```clojure
;; consumer deps.edn, with both checkouts side by side
{:deps {ct.spools/harnesses {:local/root "../harnesses.spool"}}}

;; consumer .millstrand/init.clj
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(let [runtime (current/runtime)]
  (runtime/module! runtime :millhouse/spools-identity
                   {:ns 'millhouse.spools.identity
                    :required? true})
  (runtime/module! runtime :harnesses
                   {:ns 'ct.spools.harnesses.spool
                    :after [:millhouse/spools-identity]
                    :required? true})
  (runtime/module! runtime :repo-reviewers
                   {:file "me/reviewers.clj"
                    :after [:harnesses]
                    :required? true}))
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

(lifecycle/use-reconcile! process-custody/harness-process-custody)
```

The execution resource starts after all four provider resources. Select the full
resource set when publishing asynchronous execution. Provider resources may be
selected independently with the core resource when only registration and the
Clojure API are required.

## Millhouse Workflow adapter

Workflow support is optional and separately activated. Load the Workflow engine
and complete Harnesses surface first, then activate the adapter selector:

```clojure
(runtime/module! runtime :workflow/engine
  {:ns 'millhouse.spools.workflow
   :required? true})
(runtime/module! runtime :millhouse/spools-identity
  {:ns 'millhouse.spools.identity
   :required? true})
(runtime/module! runtime :harnesses
  {:ns 'ct.spools.harnesses.spool
   :after [:millhouse/spools-identity]
   :required? true})
(runtime/module! runtime :harnesses/agent-executor
  {:ns 'ct.spools.harnesses.executors.agent.spool
   :after [:workflow/engine :harnesses]
   :required? true})
```

Place modules that register harness aliases before
`:harnesses/agent-executor`. Its resource performs an initial scan, so every
alias named by a durable ready gate must already resolve.

The selector publishes only the Workflow `:agent` executor, the
`stalled-agent-gates` query, and the adapter's event resource. It does not change
the Harnesses engine or bundled Harnesses activation.

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
System-prompt injection applies when creating a provider session and is not
replayed when resuming one.

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

Use `strand agent run <agent> --interactive` to launch an interactive tracked
session.

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

This workspace's repository lenses are in [`.millstrand/me/reviewers.clj`](.millstrand/me/reviewers.clj). The module is configured after `me.config`, which selects the reusable reviewer kind provider. A dependency coordinate only makes the namespace available; it does not activate a module. Activation is the module's typed `use-reviewer!` selection, and any agent or task coordination is a separate concern.

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

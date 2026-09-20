(ns ct.spools.harnesses.internal.agent-docs
  "Discovery prose for the tracked-agent operation."
  (:require [millstrand.api.format.alpha :as fmt]))

(def prime
  "Terse runbook projected by `strand prime agent`."
  (fmt/prose
   "
     `agent` runs tracked coding agents headlessly. Use `assign` for a work
     target, or `run --prompt` for ad-hoc work. See `strand help agent assign`
     for assignment flags.

     List currently usable agents with their resolution, model, thinking level,
     and guidance:

     ```text
     strand agent list
     ```

     List declared review lenses, then asynchronously review the current change:

     ```text
     strand agent reviewers
     strand agent review
     ```

     Review captures one bounded, immutable diff and returns ready run IDs. Use
     repeated `--agent` reviewer names or repeated `--label` values to select
     lenses. Supply literal patch content through `--git :stdin` or
     `--git :payload/diff`; this is data and is never executed.

     Assign an available provider harness or alias to a feature or one of its
     tasks. Put the instructions and completion criteria on the target first,
     and prepare its worktree before assigning. Do not preclaim it for the
     worker:

     ```text
     strand agent assign <agent> --task <target-id> --cwd <workdir>
     ```

     Assignment keeps caller, worker, reporter, and latest explicit owner as
     separate roles. It never changes ownership. Only an unowned pending
     feature receives first-claim guidance. A same-owner worker continues
     without another claim; a different owner requires an explicit handoff.
     Task guidance stays scoped to the task and never claims its parent feature
     or emits a task `kanban claim` command.

     `--cwd` is required; assignment does not create a worktree. Blocked targets
     are accepted as ready runs but stay queued until their `depends-on`
     blockers close. Independent task targets can launch concurrently while
     their `serves-root` edges retain the common feature history.

     Choose completion guidance with `--policy`:

     - `stop-on-complete` (default): leave the target open and return a result
       for coordinator acceptance. Await the run, inspect its result, then
       accept and finish the target yourself.
     - `close-on-complete`: tell the worker to finish the target with the
       supported feature or task completion command, without separate
       coordinator acceptance.

     These are the shipped policies. Workspaces can register different prose;
     the accepted policy name and exact text are frozen for the assignment.
     Policy is worker guidance, not automatic closure: process exit never
     closes the target or releases its dependents.

     Keep the returned run id. Add `--request-id <key>` when a request may be
     repeated: the same request returns the same run; conflicting reuse fails.
     Assignment rejects a competing active writer on the same target.

     Await target completion when the worker or coordinator will close it:

     ```text
     strand await --query agent-work-complete --param target=<feature-id> --min-count 1
     ```

     Use `agent-work-complete-or-intervention` with the same parameter to also
     wake for an abandoned target or its current failed run. For a whole work
     root, use `agent-work-root-complete-or-intervention` and `target=<root-id>`
     to include failed assigned descendants. These waits require positive
     evidence; no active runs or a stopped process does not mean work is done.
     With the default policy, await the run first, not the still-open feature.

     Run an ad-hoc agent and collect its work:

     ```sh
     strand agent run <agent> --prompt <prompt>
     strand await --query agent-run-terminal --param run-id=<run-id> --min-count 1
     strand agent show <run-id>
     ```

     Waiting is `strand await` on a named query, not an agent verb. Each query
     selects the run only once its condition actually holds, so a run id that
     does not exist can never satisfy these waits. `agent-run-terminal` means
     stopped or failed, not successful work. Use `agent-run-settled` when you
     also need proof the provider process is gone, as continuation requires.

     Inspect with `agent show <run-id>` (or `--task`/`--request`) and
     `agent runs --active`. Neither dumps logs.

     Stop one run by exact id with `agent stop <run-id> --reason <why>`. The
     request is durable and idempotent, and the run stays `running` until
     settlement is observed. Stopping a run does not close the work it serves.

     Inspect orphaned interactive projections without changing them:

     ```text
     strand agent reconcile <run-id> --dry-run
     ```

     Ordinary reconciliation changes only runs whose exact completion-owner
     and provider-exec PID/start fences both prove custody loss. Unknown legacy
     evidence requires `--abandon --reason <reason> --by-identity <actor>`. The
     explicit `stopped/abandoned` outcome is not settlement, records no exit
     code, retains reservations, and never permits native resume. It satisfies
     `agent-run-terminal`, not `agent-run-settled`, and requires coordinator
     intervention before any separate target-release policy.

     After an ordinary stop, await `agent-run-settled`, then update the feature and
     task notes with a primer explicitly superseding old instructions. Continue
     from the latest accepted lineage head, choosing one path:

     ```text
     strand agent resume --run-id <run-id> --prompt <continuation-prompt>
     strand agent assign <agent> --task <target-id> --cwd <workdir> --after <run-id>
     ```

     Native `resume` retains the provider, identity, session, target, settings,
     and frozen state-independent guidance; it does not create another claim.
     With `--request-id`, an exact replay returns the originally accepted child,
     even while that child is active or after the lineage advances. Reusing the
     key with a different selector, prompt, mode, transport, or actor fails.
     Name the updated primer in the continuation prompt. `assign --after`
     starts a fresh session on the same target with the predecessor's frozen
     policy and ancestor history, then evaluates current ownership for an
     explicit handoff when needed. Both require settlement. Ineligible resume
     fails; it never silently starts fresh.

     Retry a failed ad-hoc run in place after correcting its agent, cwd,
     attributes or runtime flags. `retry` refuses targeted or request-id-bound
     runs; use explicit continuation for an assignment instead.
     "
   {}))

(def about
  "Detailed orientation projected by `strand about agent`."
  (fmt/prose
   "
     `agent` manages provider-neutral, tracked coding-agent runs. It resolves
     concrete provider harnesses and workspace-defined aliases into launch
     settings, records each run as a strand, and exposes one lifecycle across
     providers. Run `strand help agent <verb>` for a verb's exact arguments and
     flags.

     Find agents available to run with:

     ```text
     strand agent list
     ```

     `agent reviewers` lists declarative review lenses and their currently
     selected aliases. `agent review` captures a repository change and creates
     all matching headless runs before scheduling them together. Results remain
     ordinary tracked runs for `agent show` and the `agent-run-*` wait queries;
     there is no built-in synthesis or semantic pass/fail step.

     The default list contains only currently available entries. Each concise
     record identifies a concrete provider harness or an alias, its selected
     resolution path, effective provider, model and thinking level, and its
     description or supported modes. Use `strand agent list --full` for the
     complete visible registry, including unavailable candidates and their
     reasons.

     Mutating commands accept `--by-identity` as operation-actor attribution.
     They store the supplied nonblank friendly string on a durable run or action
     note without requiring a local Identity match. Identity reconciliation may
     later add `attributed` edges; worker `identity/id`, native binding,
     `performed`, and explicit native `parent-of` relations stay separate.

     On `list`, a uniquely resolved caller's latest performed run selects the
     alias whose `:allow` or `:deny` visibility policy applies. An unknown,
     ambiguous, or locally resolved caller with no performed run receives an
     empty listing rather than an unfiltered one. Other reads accept the flag as
     caller context but write no attribution history.

     Workspace startup code registers aliases. An alias can layer a model,
     effort, and provider attributes over a concrete provider harness, or try
     ordered candidates selected by runtime flags. The agent passed to `run` may
     be either an available harness or an available alias.

     The configured effort usually works. Override it with `--effort`, or its
     `--thinking` synonym, when the user asks or when judgment warrants more
     reasoning. Values are model-specific, though `high` and `xhigh` are commonly
     available. `--attributes` applies a provider overlay for that run.

     Runtime flags are process-local and affect agent availability immediately:

     ```text
     strand agent config list
     strand agent config set harness/claude false
     strand agent config set seat/example true
     strand agent config unset seat/example
     ```

     Every concrete provider harness has a `harness/<name>` flag and is enabled
     unless that flag is explicitly false. Alias conditions may refer to
     additional workspace-defined flags; an unset condition flag is false.
     `unset` removes an override rather than assigning false. These settings are
     not persistent configuration, so durable defaults and aliases belong in
     workspace startup code. List agents again after changing flags to see the
     resulting alias selection and availability.

     Runs are headless by default, require a prompt, and execute asynchronously.
     A run carries a `status` of `ready`, `running`, `stopped`, or `failed`, and
     a `substatus` saying why: `pending` for a ready run, `completed`,
     `requested`, or explicit `abandoned` once stopped, and `launch`,
     `execution`, or `reconciliation` once failed. A running run has no
     substatus unless a stop is in flight. `settled` is separate and stronger:
     it means a terminal
     process fact was observed, so the provider session is provably free. A
     failed run is not automatically settled.

     `--target` binds a run to the strand it serves, `--context` carries durable
     caller data, and `--request-id` makes creation idempotent: repeating a
     request returns the same run, and reusing the key for different work fails
     and names the run already holding it. `assign` requires an explicit cwd,
     freezes the registered policy name and exact prose, keeps assignment
     separate from Kanban ownership, and queues blocked targets until their
     dependencies close.

     `stop`, `retry`, explicit reconciliation, and `self-complete` preserve each
     supplied operation actor on an immutable action note. `retry` reuses a
     failed ad hoc run after correction, and refuses runs bound to a request id
     or target, which should be continued or requested afresh instead. `resume`
     creates a *new* run continuing a settled provider
     session, reusing the predecessor's exact provider, session, and initial
     guidance rather than resolving its alias again. An exact request replay is
     checked before resolving a newer head and returns its original child.
     A new request must select the current accepted head; an ineligible or stale
     predecessor fails loudly and never falls back to a silent fresh run.

     `show` reports local session resume eligibility for the named run. That
     field does not prove the run is the accepted lineage head: a published
     child can make an otherwise locally eligible predecessor stale.

     Set `--interactive` on `run` or `resume` only when the user asks to work in
     the provider session. It launches the provider in the caller's terminal;
     `resumable` lists completed interactive runs available to continue.
     "
   {}))

(ns ct.spools.harnesses.internal.agent-docs
  "Discovery prose for the tracked-agent operation."
  (:require [millstrand.api.format.alpha :as fmt]))

(def prime
  "Terse runbook projected by `strand prime agent`."
  (fmt/prose
   "
     `agent` delegates work to tracked coding agents. Use `assign` for a feature
     or task, `run --prompt` for standalone work, and `review` for a code review.

     ## Assign work and collect the result

     1. Put instructions and completion criteria on the feature or task. Prepare
        its worktree; assignment requires `--cwd` and does not create one.
        Do not preclaim the target for the worker: assignment does not change
        ownership, and the worker receives claim or handoff guidance.
     2. Choose an available agent, then assign the target:

        ```text
        strand agent list
        strand agent assign <agent> --task <target-id> --cwd <workdir> --by-identity <your-identity>
        ```

     3. Keep the returned run ID. Await that run, then inspect its result:

        ```text
        strand await --query agent-run-terminal --param run-id=<run-id> --min-count 1
        strand agent show <run-id>
        ```

     4. Check the outcome and completion criteria before accepting the work.
        The shipped default policy, `stop-on-complete`, leaves the target open
        for you to finish after acceptance and any required delivery steps.
        Await the run first, not the still-open target.

     A terminal run is stopped or failed, not necessarily successful. Process
     exit never closes the target. `--policy close-on-complete` instead tells
     the worker to finish the target; it is guidance, not automatic closure.
     Workspaces may customize policy text.

     Blocked targets stay queued until their dependencies close. Use distinct
     task targets for parallel workers; a competing writer on the same target
     is rejected. Add `--request-id <key>` if submission may be repeated: an
     exact replay returns the same run; conflicting reuse fails.

     ## Other work

     For standalone work, launch a run and use the same await/show steps above:

     ```text
     strand agent run <agent> --prompt <prompt> --by-identity <your-identity>
     ```

     For a review, list lenses and launch the matching reviewers. Await and
     inspect each returned run ID:

     ```text
     strand agent reviewers
     strand agent review --by-identity <your-identity>
     ```

     Inspect active runs with `strand agent runs --active`. Stop a run with
     `strand agent stop <run-id> --reason <why> --by-identity <your-identity>`.
     Stopping does not close its target. Before continuation, await
     `agent-run-settled`, which also proves the provider process is gone.

     Use `strand help agent <verb>` for flags. Read `strand about agent` for
     ownership, completion policies, target waits, and stop/continuation rules.
     "
   {}))

(def about
  "Detailed orientation projected by `strand about agent`."
  (fmt/prose
   "
     `agent` manages provider-neutral, tracked coding-agent runs. It resolves
     concrete provider harnesses and workspace-defined aliases into launch
     settings and records each run as a strand. Start with `strand prime agent`
     for the delegation sequence; use `strand help agent <verb>` for exact flags.

     ## Assignment and ownership

     `assign` delegates a feature, task, or other work target. Put instructions
     and completion criteria on the target before assigning it. `--task` names
     that target; `--cwd` must name an already prepared working directory.
     Assignment does not create a worktree, claim work, or replace its reporter.

     Caller, worker, reporter, and owner are separate roles. Ownership means the
     latest explicit claim or handoff, not the most recent run or note author.
     Generated worker guidance follows that ownership history:

     - An unowned pending feature receives first-claim guidance.
     - A worker who already owns the target continues without another claim.
     - A different owner requires an explicit handoff before editing.
     - Task guidance stays scoped to the task: it neither claims the parent
       feature nor emits a task `kanban claim` command.

     Blocked targets are accepted as ready runs but stay queued until their
     `depends-on` blockers close. Assignment rejects a competing active writer
     on the same target. Independent task targets can launch concurrently;
     direct `serves` and ancestor `serves-root` edges retain their work history.

     ## Completion policies

     `--policy` selects worker guidance. These are the shipped policies:

     - `stop-on-complete` (default): the worker leaves the target open and returns
       a result for coordinator acceptance. Await the run, inspect its result,
       then finish the target only after its criteria and required delivery are
       satisfied. Do not await target closure before your own acceptance.
     - `close-on-complete`: the worker is told to finish the target using the
       supported feature or task completion command, without separate
       coordinator acceptance.

     Workspaces can register different prose, including under these names. The
     accepted policy name and exact text are frozen for the assignment. Policy
     is guidance, not enforcement: process exit never closes a target or
     releases its dependents.

     ## Waiting and inspecting

     Waiting uses `strand await`, not an agent verb. Keep the returned run ID:

     ```text
     strand await --query agent-run-terminal --param run-id=<run-id> --min-count 1
     strand agent show <run-id>
     ```

     `agent-run-terminal` selects stopped or failed runs; it does not prove
     successful work. `agent-run-settled` additionally requires evidence that
     the provider process is gone. Use it before continuing a session. A failed
     run is not automatically settled, and a nonexistent run satisfies neither
     query. Keep `--min-count 1` so an empty result cannot satisfy the wait.

     Await target completion only when a worker or coordinator will close it:

     ```text
     strand await --query agent-work-complete --param target=<target-id> --min-count 1
     ```

     Use `agent-work-complete-or-intervention` with the same parameter to also
     wake for an abandoned target or its current failed run. For a whole work
     root, use `agent-work-root-complete-or-intervention` with `target=<root-id>`
     to include failed assigned descendants. These queries require positive
     evidence; an empty active-run list or a stopped process is not completion.

     Inspect with `agent show <run-id>` (or `--task`/`--request`) and
     `agent runs --active`. Neither dumps logs. Runs have status `ready`,
     `running`, `stopped`, or `failed`. Substatus explains why: `pending` for a
     ready run; `completed`, `requested`, or `abandoned` for a stopped run; and
     `launch`, `execution`, or `reconciliation` for a failed run. Running runs
     have no substatus unless a stop is in flight.

     ## Repeated requests and continuation

     Add `--request-id <key>` when submission may be repeated. An exact replay
     returns the same run; reuse for different work fails and names the run
     holding that key. It is not a request to retry the work.

     Stop one run by exact ID:

     ```text
     strand agent stop <run-id> --reason <why> --by-identity <your-identity>
     strand await --query agent-run-settled --param run-id=<run-id> --min-count 1
     ```

     Stop requests are durable and idempotent. A running run stays running until
     settlement is observed; stopping never closes its target. After settlement,
     update the feature and task notes with instructions explicitly superseding
     the old ones. Continue from the latest accepted run in the continuation
     chain, choosing one path:

     ```text
     strand agent resume --run-id <run-id> --prompt <continuation-prompt> --by-identity <your-identity>
     strand agent assign <agent> --task <target-id> --cwd <workdir> --after <run-id> --by-identity <your-identity>
     ```

     `resume` creates a new run in the same native session, retaining provider,
     identity, target, settings, and frozen state-independent guidance. It does
     not resolve the alias again or create another claim. Name the updated notes
     in the continuation prompt.

     `assign --after` starts a fresh session on the same target, retaining the
     predecessor's frozen policy and ancestor history. It evaluates current
     ownership for an explicit handoff when needed. Both paths require a settled
     predecessor. An ineligible or stale predecessor fails; native resume never
     silently falls back to a fresh session.

     With `resume --request-id`, an exact replay returns the originally accepted
     child, even while it is active or after the chain advances. Changing the
     selector, prompt, mode, transport, or actor under that key fails. `show`
     reports local session resume eligibility, not whether a run is still the
     accepted head: a published child can make its predecessor stale.

     `retry` reuses a failed ad-hoc run after correcting its agent, cwd,
     attributes, or runtime flags. It refuses targeted or request-id-bound runs;
     use explicit continuation for assignments instead.

     ## Orphaned interactive runs

     Inspect without changing a run:

     ```text
     strand agent reconcile <run-id> --dry-run
     ```

     Ordinary reconciliation changes a run only when the recorded PID/start
     evidence for both its completion owner and provider process proves custody
     loss. Unknown legacy evidence requires explicit operator attestation with
     `--abandon --reason <reason> --by-identity <your-identity>`.

     The resulting `stopped/abandoned` state is not settlement: it records no
     exit code, retains reservations, and never permits native resume. It
     satisfies `agent-run-terminal`, not `agent-run-settled`. Coordinator
     intervention is required before any separate target-release policy.

     ## Agents, settings, and caller identity

     `strand agent list` shows currently available harnesses and aliases, their
     selected resolution, provider, model, thinking level, and guidance. Use
     `--full` to include unavailable entries and their reasons.

     Workspace startup code registers aliases. An alias can layer a model,
     effort, and provider attributes over a concrete harness, or try ordered
     candidates selected by runtime flags. Both `run` and `assign` accept an
     available harness or alias.

     The configured effort usually works. For `run`, override it with `--effort`
     or its `--thinking` synonym when needed. Values are model-specific.
     `--attributes` applies a provider overlay; consult each verb's help for its
     supported flags.

     Mutating commands accept `--by-identity` as operation-actor attribution.
     They store the supplied nonblank friendly string on a durable run or action
     note without requiring a local Identity match. Identity reconciliation may
     later add `attributed` edges; worker `identity/id`, native binding,
     `performed`, and explicit native `parent-of` relations stay separate.
     `stop`, `retry`, explicit abandonment, and `self-complete` preserve each
     supplied actor on an immutable action note.

     On `list`, a uniquely resolved caller's latest performed run selects the
     alias whose `:allow` or `:deny` visibility policy applies. An unknown,
     ambiguous, or locally resolved caller with no performed run receives an
     empty listing rather than an unfiltered one. Other reads accept caller
     context but write no attribution history.

     Runtime flags are process-local and affect availability immediately:

     ```text
     strand agent config list
     strand agent config set harness/claude false
     strand agent config set seat/example true
     strand agent config unset seat/example
     ```

     Concrete providers are enabled unless their `harness/<name>` flag is false.
     Alias conditions may refer to additional workspace flags; an unset condition
     flag is false. `unset` removes an override rather than assigning false.
     Durable defaults belong in workspace startup code. List agents again after
     changing flags to see the resulting selection and availability.

     ## Standalone runs and reviews

     `run --prompt` starts asynchronous headless work by default. `--target`
     binds it to a strand, while `--context` carries durable caller data.

     `agent reviewers` lists declared review lenses and their selected aliases.
     `agent review` captures one bounded, immutable diff and creates matching
     headless runs before scheduling them together. Use repeated `--agent`
     reviewer names or repeated `--label` values to select lenses. Supply literal
     patch content through `--git :stdin` or `--git :payload/diff`; it is data,
     never executed. Collect each result with the ordinary run waits and `show`.
     There is no built-in synthesis or semantic pass/fail step.

     Set `--interactive` on `run` or `resume` only when the user asks to work in
     the provider session. It launches the provider in the caller's terminal;
     `resumable` lists completed interactive runs available to continue.
     "
   {}))

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
(runtime/module! runtime :harnesses
  {:ns 'ct.spools.harnesses.spool
   :spools ['ct.spools/harnesses 'millhouse.spools/identity]
   :required? true})
```

This publishes:

- the `agent` operation;
- the `agent` bin;
- the headless-run event handler;
- the core, provider, and execution resources;
- process-custody reconciliation.

Loading `ct.spools.harnesses`, a provider namespace, or one of the execution
namespaces alone does not publish those declarations.

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

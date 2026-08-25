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

- the `harness` operation;
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
            [ct.spools.harnesses.agent-cli :as agent-cli]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.process-custody :as process-custody]
            [ct.spools.harnesses.providers.claude :as claude]
            [ct.spools.harnesses.providers.codex :as codex]
            [ct.spools.harnesses.providers.cursor :as cursor]
            [ct.spools.harnesses.providers.pi :as pi]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/use-op! agent-cli/harness)
(millstrand/use-handler! execution/on-event)
(millstrand/use-bin! agent-cli/agent)

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

Provider options are stored as harness overlay attributes:

| Provider | Attributes |
| --- | --- |
| Claude | `harness.claude/model`, `harness.claude/effort`, `harness.claude/extra-argv` |
| Codex | `harness.codex/model`, `harness.codex/reasoning-effort`, `harness.codex/extra-argv` |
| Cursor | `harness.cursor/model`, `harness.cursor/extra-argv` |
| Pi | `harness.pi/model`, `harness.pi/thinking`, `harness.pi/extra-argv` |

Register aliases with a documented descriptor. Portable thinking is limited to
`:low`, `:medium`, and `:high`; each provider maps those levels to its native
attribute. An explicit provider attribute takes precedence over portable
thinking.

```clojure
(harnesses/register-alias!
 runtime :terra
 {:doc "Use the Terra model with medium thinking."
  :parent :pi
  :thinking :medium
  :attributes {:harness.pi/model "openai-codex/gpt-5.6-terra"}})
```

Aliases may name another alias as their parent. Concrete harnesses must declare
a portable-thinking mapping before an alias using `:thinking` can resolve.

Use `strand harness list` to inspect registered concrete harnesses and documented
aliases, or `mill bin run agent --help` to launch an interactive tracked session.

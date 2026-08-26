(ns me.config
  "Configure the Harnesses spool for this workspace."
  (:require [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.agent-cli :as agent-cli]
            [ct.spools.harnesses.execution :as execution]
            [ct.spools.harnesses.process-custody :as process-custody]
            [ct.spools.harnesses.providers.claude :as claude]
            [ct.spools.harnesses.providers.codex :as codex]
            [ct.spools.harnesses.providers.cursor :as cursor]
            [ct.spools.harnesses.providers.pi :as pi]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(def ^:private alias-definitions
  {:deepseek-flash
   {:doc (format-alpha/prose
          "
            Scores: complexity X; code-taste X; resilience X; ui-design X;
            coordination -; cost 9.

            DeepSeek v4 Flash via Pi. Enumeration-shaped recon and quota fallback at
            very low cost. It won millstrand's wide fan-out explore arm but loses
            precision on deep traces and exact citations; verify citations and do
            not route load-bearing deep dives here.
            " {}),
    :parent :pi,
    :model "deepseek/deepseek-v4-flash"
    :effort :high
    :attributes {:harness/extra-argv ["--agent" "main"]}}
   :luna
   {:doc (format-alpha/prose
          "
            Scores: complexity 3; code-taste 4; resilience 1; ui-design 2;
            coordination -; cost 9.

            Ideal for implementation details, scouting and delegated focussed tasks
    " {}),
    :parent :pi,
    :model "openai-codex/gpt-5.6-luna"
    :effort :high,
    :attributes {}},
   :opus
   {:doc (format-alpha/prose
          "
            Scores: complexity 8; code-taste 9; resilience X; ui-design 9;
            coordination 6; docs-prose 7; cost 2.

            Claude Opus. Greenfield features, API design, and critical seams;
            archaeology-first and strongest on known-work code quality. Keep
            cross-vendor GPT sign-off for Opus-authored changes. Suitable for
            agent-facing docs, though the docs bake-off found its restructuring
            paste-up-prone.
            " {}),
    :parent :claude,
    :model "opus"
    :effort :low,
    :attributes {}},
   :oracle
   {:doc (format-alpha/prose
          "
            Scores: complexity 9; code-taste 9; resilience X; ui-design 8;
            coordination 9; docs-prose 9; cost 1.

            Claude Fable. Reserve for extreme diagnosis, top-of-graph coordination,
            user-facing prose where writing is the product, and bench judging. Brief
            one case per run and require incremental notes: a context-overflowed run
            that writes nothing is unusually expensive.
            " {}),
    :parent :claude,
    :model "claude-fable-5"
    :effort :high,
    :attributes {}},
   :sol
   {:doc (format-alpha/prose
          "
            Scores: complexity 6; code-taste 6; resilience 9; ui-design 5;
            coordination X; cost 5.

            gpt-5.6-sol low via Codex. General build and default delegation seat,
            including diff-shaped refactors. It was the only model to ship a passing
            gate under every benched environment condition, recovering hostile
            toolchains when needed.
    " {}),
    :parent :pi,
    :model "openai-codex/gpt-5.6-sol"
    :effort :low,
    :attributes {}},
   :terra
   {:doc (format-alpha/prose
          "
            Scores: complexity 5; code-taste 7; resilience 2; ui-design 4;
            coordination 7; cost 7.

            gpt-5.6-terra medium via Codex. Well-defined single-concern review
            and validation on clean checkouts; benched the cleanest test-writing
            of the Codex tiers at about 40% of Sol's price. It missed
            cross-package fallout when tests could not run and gives up on
            broken toolchains.
    " {}),
    :parent :pi,
    :model "openai-codex/gpt-5.6-terra"
    :effort :medium,
    :attributes {}},
   :tui
   {:doc "Primary user agent",
    :parent :sol
    :effort :low
    :attributes {}}})

(defn open-harnesses!
  "Register the shared Codethread-inspired harness seat map."
  [{:keys [runtime]}]
  (let [registrations
        (mapv (fn [[alias descriptor]]
                (harnesses/register-alias! runtime alias descriptor))
              alias-definitions)]
    {:opened :harnesses
     :aliases (mapv :alias registrations)}))

(defn close-harnesses!
  "Remove this workspace's harness seat registrations."
  [{:keys [runtime resource]}]
  (doseq [alias (:aliases resource)]
    (harnesses/unregister-alias! runtime alias))
  {:closed :harnesses})

(lifecycle/defresource! config-harness-runtime
  "Register current harnesses"
  {:open 'me.config/open-harnesses!
   :close 'me.config/close-harnesses!
   :after #{:harness-core-runtime}})

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

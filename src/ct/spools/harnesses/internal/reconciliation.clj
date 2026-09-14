(ns ct.spools.harnesses.internal.reconciliation
  "Pure decisions and patches for interactive-run reconciliation."
  (:require [ct.spools.harnesses.internal.lifecycle :as life]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(def ^:private automatically-observable-harnesses
  #{"codex" "pi"})

(defn classification
  "Classify one interactive run from current owner and provider evidence.

  `:orphaned` means both the completion owner and the original provider exec
  are positively gone or PID-replaced. It does not claim that a remote provider
  backend exited. Missing or unavailable observations remain `:unknown`.
  Positive native, owner, or provider liveness always protects the run."
  [run {:keys [completion-owner provider native active-session-writers
               session-writers]}]
  (let [gone? #(contains? #{"gone" "replaced"} (:state %))]
    (cond
      (life/terminal? run)
      {:classification "terminal"
       :reason "run is already terminal"}

      (not= "running" (life/status run))
      {:classification "ineligible"
       :reason "only running interactive attempts can be reconciled"}

      (seq active-session-writers)
      {:classification "protected"
       :reason "another active run holds the native session"}

      (= "active" (:state native))
      {:classification "live"
       :reason "a native process currently names this session"}

      (= "live" (:state provider))
      {:classification "live"
       :reason "the recorded provider process and start fence still match"}

      (= "live" (:state completion-owner))
      {:classification "protected"
       :reason "the completion owner is live and may still report provider exit"}

      (not (contains? automatically-observable-harnesses
                      (attr-get run :harness/harness)))
      {:classification "unknown"
       :reason "this maintenance-mode provider has no managed exec evidence"}

      (or (= "unavailable" (:state native))
          (= "unavailable" (:state session-writers))
          (contains? #{"missing" "remote" "unavailable"} (:state provider))
          (contains? #{"missing" "remote" "unavailable"}
                     (:state completion-owner)))
      {:classification "unknown"
       :reason "complete local owner/provider evidence is unavailable"}

      (and (gone? provider) (gone? completion-owner))
      {:classification "orphaned"
       :reason "the original provider exec and its completion owner are gone"}

      :else
      {:classification "unknown"
       :reason "no conclusive owner/provider evidence exists"})))

(defn action
  "Choose the supported reconciliation action for a classification.

  Proven local owner and provider loss is automatically abandoned. An operator
  may explicitly attest abandonment for unknown legacy evidence. Live,
  protected, ineligible, and terminal runs are never mutable here."
  [{:keys [classification]} {:keys [abandon?]}]
  (if (or (= "orphaned" classification)
          (and abandon? (= "unknown" classification)))
    "abandon"
    "preserve"))

(defn abandonment-patch
  "Return the terminal audit patch for an explicit abandonment outcome.

  Abandonment is not process settlement: the patch has no exit code, disables
  native resume, and retains both target and session reservations through the
  existing unsettled-terminal contract."
  [run {:keys [at by reason source evidence]}]
  {:state "closed"
   :attributes
   (cond->
    {:harness/status "stopped"
     :harness/substatus "abandoned"
     :harness/settled "false"
     :harness/settlement "interactive-abandoned"
     :harness/settlement-gap
     "Interactive custody was abandoned; provider/backend exit is unproven"
     :harness/session-usable "false"
     :harness/abandoned-at at
     :harness/reconciled-at at
     :harness/abandoned-by by
     :harness/abandon-reason reason
     :harness/reconciliation-source source
     :harness/reconciliation-evidence evidence}
     (attr-get run :harness/invocation)
     (assoc :harness/reconciled-invocation
            (attr-get run :harness/invocation)))})

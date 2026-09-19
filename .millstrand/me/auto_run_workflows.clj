(ns harnesses.auto-run-workflows
  "Repository-owned autonomous delivery for automatically assigned features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.land.autonomous :as autonomous]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.format.alpha :as format]))

(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::card ::text)
(s/def ::feature ::text)
(s/def ::branch ::text)
(s/def ::worktree ::text)
(s/def ::params (s/keys :req-un [::card ::feature ::branch ::worktree]))

(defn- failure-instruction [{:keys [card]}]
  (autonomous/failure-policy card))

(defn- shell-gate [id title dependencies argv timeout]
  (workflow/gate id title :shell
                 :depends-on dependencies
                 :attributes {"shell/argv" argv
                              "shell/cwd" (fn [{:keys [worktree]}] worktree)
                              "shell/timeout-secs" timeout}
                 failure-instruction))

(workflow/defworkflow! auto-full-land
  "Implement, verify, review, then hand landing to an independent finisher."
  {:entrypoints #{:start} :param-spec ::params}
  (workflow/workflow
   "Deliver automatically"
   (workflow/step
    :implement "Implement and verify the assigned feature" :self
    (fn [{:keys [card]}]
      (format/prose
       "
         Read card {card}, its epic and tasks, and AGENTS.md. Claim the card
         with your provided identity, branch, worktree, and Harnesses run ID.
         Work in the provided worktree; do not create a second one. Implement
         the scoped outcome yourself and record evidence on the card's tasks.
         Follow the architecture contract and preserve unrelated work.

         Use disposable worlds for workspace-backed tests. Never mutate the
         shared `.millstrand` world as a fixture. Add focused regression tests
         when behavior or ownership boundaries warrant them.

         Run `make check` while iterating. Commit the verified work, then
         complete this step. Do not start Land; the following steps own review
         and the independent landing handoff.

         {failure-policy}
       " {:card card :failure-policy (autonomous/failure-policy card)})))
   (shell-gate :quality "Pass repository quality checks" [:implement]
               ["make" "check"] 5400)
   (workflow/step
    :prepare-pr "Publish the exact change with its review package" :self
    :depends-on [:quality]
    (fn [{:keys [card branch]}]
      (format/prose
       "
         Push {branch} and create or update its PR against main. It must be
         ready for review, not a draft. The PR body must contain these exact
         nonempty Markdown sections:

         - `## Summary`: outcome, scope, and important decisions.
         - `## Walkthrough`: explain the affected boundaries and data flow.
         - `## Verification`: automated checks, reproduction or manual test
           instructions, and limitations.

         Put the PR URL, exact head SHA, and concise handoff on card {card}.
         Retain detailed evidence on its verification task. Complete this step
         only after publishing the committed revision and review package. The
         next gates independently wait for CI and verify the card transition.
       " {:card card :branch branch})))
   (shell-gate :ci "Wait for the PR checks" [:prepare-pr]
               (fn [{:keys [branch]}]
                 ["gh" "pr" "checks" branch "--watch" "--fail-fast"])
               2100)
   (workflow/gate
    :review-card "Move the verified feature into review" :code
    :depends-on [:ci]
    :attributes {"code/fn" "millhouse.spools.land.card-actions/review-card!"
                 "code/params" (fn [{:keys [card]}] {:card card})}
    failure-instruction)
   (workflow/call :land #'autonomous/autonomous-land {}
                  :depends-on [:review-card]
                  :title "Review and hand off autonomous landing")))

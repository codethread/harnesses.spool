(ns harnesses.auto-run-workflows
  "Repository-owned autonomous delivery for automatically assigned features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.auto-run-land :as autonomous]
            [millhouse.spools.land.support :as land-support]
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

(def ^:private human-gate-instruction
  "Await this executor-owned gate. Inspect failures, repair the cause, then explicitly clear gate/error to retry. Never manually assert a passing result.")

(defn- delivery [autonomous?]
  (let [gate-failure (if autonomous? failure-instruction human-gate-instruction)]
    (apply
     workflow/workflow
     (if autonomous? "Deliver automatically" "Prepare for human review")
     (concat
      [(workflow/step
        :implement "Implement and verify the assigned feature" :self
        (fn [{:keys [card]}]
          (format/prose
           "
             Read card {card}, its epic and tasks, and AGENTS.md. Inspect current
             ownership before claiming: continue if you already own it; claim only
             an unowned pending feature with your provided identity, branch,
             worktree, and run ID. Another owner requires an explicit handoff.
             Work in the provided worktree; do not create a second one. Implement
             the scoped outcome yourself and record evidence on the card's tasks.
             Follow the architecture contract and preserve unrelated work.

             Use disposable worlds for workspace-backed tests. Never mutate the
             shared `.millstrand` world as a fixture. Add focused regression tests
             when behavior or ownership boundaries warrant them.

             Run focused checks while iterating. Commit the completed work, then
             complete this step. Do not start Land; the following steps own
             publication, quality, and review.

             {failure-policy}
           " {:card card
              :failure-policy (if autonomous?
                                (autonomous/failure-policy card)
                                "")})))
       (workflow/step
        :publish "Publish the committed branch before quality checks" :self
        :depends-on [:implement]
        (fn [{:keys [branch]}]
          (format/prose
           "
             Publish the committed branch and establish its upstream before
             repository quality runs:

             ```sh
             git push --set-upstream origin {branch}
             ```

             Do not amend, commit, or otherwise change HEAD after this step. The
             following quality gate must validate this published revision.
           " {:branch branch})))
       (land-support/shell-gate
        :quality "Pass repository quality checks" [:publish]
        (fn [{:keys [branch]}]
          ["sh" ".millstrand/published-candidate.sh" branch])
        5400 gate-failure)
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

             Read the quality receipt from the Git metadata path returned by
             `git rev-parse --git-path millstrand-land-quality-head`. Compare its
             SHA with `git rev-parse HEAD` and the PR head. If they differ, stop
             and repair publication and quality; do not claim this revision was
             tested.

             Put the PR URL, exact head SHA, and concise handoff on card {card}.
             Retain detailed evidence on its verification task. Complete this step
             only after publishing the committed revision and review package. The
             next gate independently verifies the PR and waits for CI. Keep the
             card claimed during autonomous work; only the human route requests
             human attention.
           " {:card card :branch branch})))
       (land-support/shell-gate
        :ci "Wait for the PR checks" [:prepare-pr]
        (fn [{:keys [branch]}]
          (land-support/pr-checks-argv "allow-empty" branch))
        2100 gate-failure)]
      (when-not autonomous?
        [(workflow/gate
          :review-card "Move the verified feature into review" :code
          :depends-on [:ci]
          :attributes {"code/fn" "millhouse.spools.land.card-actions/review-card!"
                       "code/params" (fn [{:keys [card]}] {:card card})}
          "This is an automatic card transition after the review-package checks.")])
      (if autonomous?
        [(workflow/call :land #'autonomous/autonomous-land {}
                        :depends-on [:ci]
                        :title "Review and hand off autonomous landing")]
        [(workflow/checkpoint
          :human-acceptance "Human review: return the passing PR and stop"
          :depends-on [:review-card]
          :kind :human
          :choices [{:key :reviewed :label "Human review recorded"}]
          :attributes
          {"workflow/instruction"
           (format/prose
            "
              Stop here and return the PR URL, exact head SHA, walkthrough,
              verification evidence, and open questions. Do not choose this checkpoint,
              merge, start Land, finish the card, launch a finisher, remove the
              worktree, or remain running to poll for the user.

              The user will review and decide what happens next. Generic landing
              instructions do not override this explicit stop boundary.
            " {})})])))))

(workflow/defworkflow! auto-human-review
  "Prepare a passing, documented PR and stop for the user's full review."
  {:entrypoints #{:start} :param-spec ::params}
  (delivery false))

(workflow/defworkflow! auto-full-land
  "Implement, publish, verify, review, then hand landing to an independent finisher."
  {:entrypoints #{:start} :param-spec ::params}
  (delivery true))

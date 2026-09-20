(ns harnesses.auto-run-workflows
  "Repository-owned autonomous delivery for automatically assigned features."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.land.autonomous :as autonomous]
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

(defn- replace-quality-check [script old-check new-check]
  (if (str/includes? script old-check)
    (str/replace-first script old-check new-check)
    (throw (ex-info "Land quality gate no longer contains the expected check"
                    {:check old-check}))))

(def ^:private upstream-quality-check
  (str/join
   "\n"
   ["  upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null) \\"
    "    || die \"branch $target has no upstream; push it before running the land quality gate\""
    "  upstream_head=$(git rev-parse \"$upstream\") || die \"cannot read upstream $upstream\""
    "  [ \"$upstream_head\" = \"$head_before\" ] \\"
    "    || die \"unpushed or mismatched HEAD: local $head_before, upstream $upstream_head\""]))

(def ^:private origin-quality-check
  (str/join
   "\n"
   ["  git fetch origin \"refs/heads/$target:refs/remotes/origin/$target\" \\"
    "    || die \"cannot refresh refs/remotes/origin/$target from origin\""
    "  origin_head=$(git rev-parse \"refs/remotes/origin/$target\") \\"
    "    || die \"cannot read refreshed refs/remotes/origin/$target\""
    "  [ \"$origin_head\" = \"$head_before\" ] \\"
    "    || die \"unpushed or mismatched HEAD: local $head_before, origin $origin_head\""]))

(def ^:private upstream-quality-check-after
  (str/join
   "\n"
   ["  upstream_head_after=$(git rev-parse \"$upstream\") \\"
    "    || die \"cannot re-read upstream $upstream after quality checks\""
    "  [ \"$upstream_head_after\" = \"$head_before\" ] \\"
    "    || die \"upstream changed during quality checks: expected $head_before, found $upstream_head_after\""]))

(def ^:private origin-quality-check-after
  (str/join
   "\n"
   ["  git fetch origin \"refs/heads/$target:refs/remotes/origin/$target\" \\"
    "    || die \"cannot refresh refs/remotes/origin/$target from origin after quality checks\""
    "  origin_head_after=$(git rev-parse \"refs/remotes/origin/$target\") \\"
    "    || die \"cannot re-read refreshed refs/remotes/origin/$target after quality checks\""
    "  [ \"$origin_head_after\" = \"$head_before\" ] \\"
    "    || die \"origin/$target changed during quality checks: expected $head_before, found $origin_head_after\""]))

(def ^:private auto-run-quality-gate-script
  (-> land-support/land-quality-gate-script
      (replace-quality-check upstream-quality-check origin-quality-check)
      (replace-quality-check upstream-quality-check-after origin-quality-check-after)))

(workflow/defworkflow! auto-full-land
  "Implement, publish, verify, review, then hand landing to an independent finisher."
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

         Run focused checks while iterating. Commit the completed work, then
         complete this step. Do not start Land; the following steps own
         publication, quality, review, and the independent landing handoff.

         {failure-policy}
       " {:card card :failure-policy (autonomous/failure-policy card)})))
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
      (land-support/sh-gate auto-run-quality-gate-script
                            "auto-run-quality" branch))
    5400 failure-instruction)
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
   (land-support/shell-gate
    :ci "Wait for the PR checks" [:prepare-pr]
    (fn [{:keys [branch]}]
      (land-support/pr-checks-argv "allow-empty" branch))
    2100 failure-instruction)
   (workflow/gate
    :review-card "Move the verified feature into review" :code
    :depends-on [:ci]
    :attributes {"code/fn" "millhouse.spools.land.card-actions/review-card!"
                 "code/params" (fn [{:keys [card]}] {:card card})}
    failure-instruction)
   (workflow/call :land #'autonomous/autonomous-land {}
                  :depends-on [:review-card]
                  :title "Review and hand off autonomous landing")))

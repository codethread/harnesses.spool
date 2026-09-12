(ns me.reviewers
  "Declare the review lenses enabled for this workspace.

  Declarations stay inert until this module selects them. The Harnesses spool
  owns the reviewer kind; this workspace owns which repository lenses are
  available to `strand agent reviewers` and `strand agent review`."
  (:require [ct.spools.harnesses.reviewers :as reviewers]
            [millstrand.api.format.alpha :as format-alpha]))

(reviewers/defreviewer!
  source-form
  "Check Clojure readability and source prose."
  {:seat ['reviewer 'luna]
   :labels ["PR" "Clojure" "Readability"]
   :glob ["src/**"]
   :system-prompt
   (format-alpha/prose
    "
     This is a judgment-focused source-form review. Prefer a clear public
     story, named steps, and prose that is easy to scan over stylistic
     nitpicks. Do not duplicate the correctness or test-coverage review."
    {})}
  (format-alpha/prose
   "
    Review changed Clojure source for readability and source prose, not for
    the test suite. Check that public entry points lead the reader through
    named steps, that helpers do not hide important control flow, and that
    docstrings, comments, and long prose values follow the surrounding source
    style. Check source prose for clear paragraphs and actionable wording.

    Report only concrete P1/P2 readability defects with repository-relative
    paths and line numbers, plus a practical rewrite or restructuring. Do not
    turn preferences into findings. Explicitly say `No findings` when the
    changed source is clear. Do not edit files or repository state."
   {}))

(reviewers/defreviewer!
  docs-and-tests
  "Check contract coverage in docs and tests."
  {:seat ['luna 'reviewer]
   :labels ["PR" "Docs" "Tests"]
   :glob ["README.md" "docs/**" "src/**" "test/**"]}
  (format-alpha/prose
   "
    Review the changed files for contract coverage.

    Check README and relevant docs for commands, public names, and behavior
    that the patch changes. Check source and tests for a focused, meaningful
    proof of the promised behavior; do not demand tests for claims they cannot
    establish. Flag omissions only when the changed contract needs them.

    Report concrete P1/P2 findings with repository-relative paths and line
    numbers, followed by a practical fix. Explicitly say `No findings` when
    the diff is adequate. Do not edit files or repository state."
   {}))

(reviewers/defreviewer!
  runtime-correctness
  "Check correctness of changed Clojure runtime behavior."
  {:seat ['luna 'reviewer]
   :labels ["PR" "Correctness" "Clojure"]
   :glob ["src/**"]}
  (format-alpha/prose
   "
    Trace the changed Clojure runtime behavior through its public seam and
    the adjacent paths it touches. Look for incorrect state transitions,
    boundary handling, error behavior, resource ordering, and concurrency
    mistakes. Verify claims against the changed code rather than proposing
    unrelated improvements.

    Report only actionable P1/P2 defects you can support with repository-
    relative paths and line numbers, and explain the smallest practical fix.
    Explicitly say `No findings` when the behavior is sound. Do not edit files
    or repository state."
   {}))

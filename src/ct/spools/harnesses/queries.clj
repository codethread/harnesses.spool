(ns ct.spools.harnesses.queries
  "Named positive-evidence queries for waiting on tracked agent runs.

  Every query below selects a target only when the condition it names actually
  holds. Waiting is Weaver's job, not the agent operation's."
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(def ^:private run-attr
  [:= [:attr "harness/run"] "true"])

(def ^:private terminal-statuses ["stopped" "failed"])

(millstrand/defquery agent-run-terminal
  "Select run `run-id` once it has reached a terminal status.

  Terminal means the run will not do more work. It does not prove that the
  provider process is gone; await `agent-run-settled` for that.
  "
  {}
  {:params [:run-id]
   :where [:and
           run-attr
           [:= :id [:param :run-id]]
           [:in [:attr "harness/status"] terminal-statuses]]})

(millstrand/defquery agent-run-settled
  "Select run `run-id` once it has positive settlement evidence.

  This is the query to await before resuming a native session.
  "
  {}
  {:params [:run-id]
   :where [:and
           run-attr
           [:= :id [:param :run-id]]
           [:in [:attr "harness/status"] terminal-statuses]
           [:= [:attr "harness/settled"] "true"]]})

(millstrand/defquery agent-run-active
  "Select run `run-id` while it is ready or running.

  Await it with `--max-count 0` to block until that one run leaves the active
  set for any reason.
  "
  {}
  {:params [:run-id]
   :where [:and
           run-attr
           [:= :id [:param :run-id]]
           [:in [:attr "harness/status"] ["ready" "running"]]]})

(millstrand/defquery agent-runs-active
  "Select every published run that is ready or running."
  {}
  [:and
   run-attr
   [:= [:attr "harness/published"] "true"]
   [:in [:attr "harness/status"] ["ready" "running"]]])

(millstrand/defquery agent-runs-for-target
  "Select every run serving the strand named by `target`."
  {}
  {:params [:target]
   :where [:and
           run-attr
           [:edge/out "serves" [:= :id [:param :target]]]]})

(def ^:private work-target
  [:= :id [:param :target]])

(def ^:private done-target
  "Positive completion evidence for a target card or generic work strand."
  [:and
   [:= :state "closed"]
   [:or [:= [:attr "kanban/outcome"] "done"]
    [:missing [:attr "kanban/outcome"]]]])

(def ^:private abandoned-target
  [:= [:attr "kanban/outcome"] "abandoned"])

(def ^:private current-accepted-run
  [:and
   run-attr
   [:= [:attr "harness/published"] "true"]
   [:or [:= [:attr "harness/continued"] "false"]
    [:missing [:attr "harness/continued"]]]])

(def ^:private failed-serving-run
  [:edge/in "serves"
   [:and
    current-accepted-run
    [:= [:attr "harness/status"] "failed"]]])

(def ^:private target-intervention
  [:or abandoned-target failed-serving-run])

(def ^:private root-descendant-intervention
  [:edge/in "serves-root"
   [:or
    [:and
     current-accepted-run
     [:= [:attr "harness/status"] "failed"]]
    [:and
     abandoned-target
     [:missing [:attr "harness/run"]]]]])

(millstrand/defquery agent-work-complete
  "Select a target after known done completion.

  Abandoned cards, empty active sets, stopped runs, and superseded failed
  predecessors do not satisfy this query.
  "
  {}
  {:params [:target]
   :where [:and work-target done-target]})

(millstrand/defquery agent-work-complete-or-intervention
  "Select a target after done completion or explicit intervention evidence.

  An abandoned card or the current failed serving head requires intervention.
  A stopped run and a failed predecessor with an accepted child do not qualify.
  "
  {}
  {:params [:target]
   :where [:and
           work-target
           [:or done-target target-intervention]]})

(millstrand/defquery agent-work-root-complete
  "Select a work root after its own positive done outcome."
  {}
  {:params [:target]
   :where [:and work-target done-target]})

(millstrand/defquery agent-work-root-complete-or-intervention
  "Select a work root after completion or descendant intervention.

  A root may remain active while a failed descendant needs coordinator action;
  the root query observes that descendant rather than requiring an empty active
  set.
  "
  {}
  {:params [:target]
   :where [:and
           work-target
           [:or done-target target-intervention root-descendant-intervention]]})

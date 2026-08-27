(ns ct.spools.harnesses.agent-bin
  "Interactive coding-agent bin declaration."
  (:refer-clojure :exclude [agent])
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defbin agent
  "Open a coding agent in the caller's terminal as a tracked interactive run."
  {:executable [:root "bin/agent"]})

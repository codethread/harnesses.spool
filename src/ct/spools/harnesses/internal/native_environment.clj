(ns ct.spools.harnesses.internal.native-environment
  "Remove inherited native identity ownership from provider launches.")

(def ownership-keys
  "Environment keys that must not configure a new Pi identity."
  ["MILLSTRAND_AGENT_ID" "MILLSTRAND_RESERVATION_ID"
   "MILLSTRAND_MANAGED_BOOTSTRAP" "MILLSTRAND_MANAGED_GUIDANCE"
   "MILLSTRAND_BOOTSTRAP_V1" "MILLSTRAND_IDENTITY_TRANSPORT"
   "MILLSTRAND_PI_WORKSPACE" "MILLSTRAND_WORKSPACE"
   "MILLSTRAND_PI_PARENT_IDENTITY" "MILLSTRAND_INVOCATION"
   "PI_SUBAGENT"])

(defn scrub-command
  "Execute argv without inherited ownership while retaining minimal run correlation."
  [argv]
  (into (into ["env"] (mapcat #(vector "-u" %) ownership-keys)) argv))

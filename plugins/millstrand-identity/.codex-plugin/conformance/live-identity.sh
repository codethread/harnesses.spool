#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
source_plugin_root="$repo_root/plugins/millstrand-identity"
plugin_root=
identity_hook=
identity_sha="62723b7b1820c7e1723de4a2ff985b069871159e"
identity_url="https://github.com/codethread/millhouse.spool.git"

tmp_root=$(mktemp -d /tmp/cia.XXXXXX)
state_root=$(mktemp -d /tmp/cis.XXXXXX)
project="$tmp_root/project"
linked_project="$tmp_root/linked-project"
workspace="$project/.millstrand"
mill_pid=
mill_drain_pid=
weaver_started=0

cleanup() {
	if [[ "$weaver_started" == 1 ]]; then
		mill weaver stop --workspace "${workspace:?}" >/dev/null 2>&1 || true
	fi
	if [[ -n "$mill_pid" ]] && kill -0 "$mill_pid" 2>/dev/null; then
		kill "$mill_pid" 2>/dev/null || true
		wait "$mill_pid" 2>/dev/null || true
	fi
	if [[ -n "$mill_drain_pid" ]] && kill -0 "$mill_drain_pid" 2>/dev/null; then
		kill "$mill_drain_pid" 2>/dev/null || true
		wait "$mill_drain_pid" 2>/dev/null || true
	fi
	rm -rf -- "${tmp_root:?}" "${state_root:?}"
}
trap cleanup EXIT INT TERM

for command in codex git jq mill strand; do
	command -v "$command" >/dev/null 2>&1 || {
		echo "live identity acceptance requires $command" >&2
		exit 1
	}
done

mkdir -p "$workspace" "$tmp_root/home" "$tmp_root/codex" "$tmp_root/cache" "$tmp_root/gitlibs" "$tmp_root/runtime"
export HOME="$tmp_root/home"
export CODEX_HOME="$tmp_root/codex"
export XDG_CONFIG_HOME="$tmp_root/config"
export XDG_STATE_HOME="$state_root"
export XDG_CACHE_HOME="$tmp_root/cache"
export XDG_RUNTIME_DIR="$tmp_root/runtime"
export GITLIBS="$tmp_root/gitlibs"
export CODEX_APP_SERVER_DISABLE_MANAGED_CONFIG=1
plugin_root="$CODEX_HOME/plugins/cache/harnesses/millstrand-identity/local"
mkdir -p "$(dirname "$plugin_root")"
cp -R "$source_plugin_root" "$plugin_root"
identity_hook="$plugin_root/.codex-plugin/hooks/identity.sh"
cat >"$CODEX_HOME/config.toml" <<'EOF'
[features]
plugins = true
remote_plugin = false
hooks = true

[plugins."millstrand-identity@harnesses"]
enabled = true
EOF

git init --quiet "$project"
git -C "$project" config user.name "Codex Identity Acceptance"
git -C "$project" config user.email "codex-identity@example.invalid"
printf '%s\n' '.millstrand/' >"$project/.gitignore"
git -C "$project" add .gitignore
git -C "$project" commit --quiet -m fixture
git -C "$project" worktree add --quiet -b live-linked "$linked_project"
mkdir -p "$project/nested/cwd" "$linked_project/nested/cwd"

cat >"$workspace/deps.edn" <<EOF
{:deps
 {ct.spools/harnesses {:local/root "$repo_root"}
  millhouse.spools/identity
  {:git/url "$identity_url"
   :git/sha "$identity_sha"
   :deps/root "spools/identity"}}}
EOF
cat >"$workspace/init.clj" <<'EOF'
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime]
         '[ct.spools.harnesses.agent-cli])
(def runtime (current/runtime))
(runtime/module! runtime :millhouse/spools-identity
                 {:ns 'millhouse.spools.identity
                  :required? true})
(runtime/module! runtime :harnesses-registration
                 {:file "registration.clj" :after [:millhouse/spools-identity]
                  :required? true})
EOF
cat >"$workspace/registration.clj" <<'EOF'
(ns registration
  (:require [ct.spools.harnesses :as harnesses]
            [ct.spools.harnesses.agent-cli :as agent-cli]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))
(lifecycle/use-resource! harnesses/harness-core-runtime)
(millstrand/use-op! agent-cli/agent)
EOF

mill_log="$tmp_root/mill.log"
exec 3< <(exec mill start --json 2>&1)
mill_pid=$!
mill_ready_line=
read_status=0
read_deadline=$((SECONDS + 15))
while :; do
	read_timeout=$((read_deadline - SECONDS))
	if ((read_timeout <= 0)); then
		read_status=142
		break
	fi
	if IFS= read -r -t "$read_timeout" mill_line <&3; then
		printf '%s\n' "$mill_line" >>"$mill_log"
		if jq -e '(.message // "") | startswith("Mill ready (")' <<<"$mill_line" >/dev/null 2>&1; then
			mill_ready_line=$mill_line
			break
		fi
	else
		read_status=$?
		break
	fi
done
if [[ -z "$mill_ready_line" ]]; then
	echo "disposable Mill did not become ready (read status $read_status)" >&2
	sed -n '1,160p' "$mill_log" >&2
	exit 1
fi
cat <&3 >>"$mill_log" &
mill_drain_pid=$!

mill status --json | jq -e '.healthy == true' >/dev/null
mill init --workspace "$workspace" >/dev/null
mill weaver start --workspace "$workspace" >/dev/null
weaver_started=1

session_payload() {
	local session_id=$1
	local source=$2
	local payload_cwd=${3:-$project}
	jq -cn --arg session "$session_id" --arg cwd "$payload_cwd" --arg source "$source" \
		'{session_id: $session, cwd: $cwd, hook_event_name: "SessionStart", source: $source, model: "live-fixture"}'
}

subagent_payload() {
	local session_id=$1
	local agent_id=$2
	jq -cn --arg session "$session_id" --arg agent "$agent_id" --arg cwd "$project" \
		'{session_id: $session, cwd: $cwd, hook_event_name: "SubagentStart", turn_id: "live-turn", agent_id: $agent, agent_type: "default", model: "live-fixture"}'
}

invoke_explicit() {
	local payload=$1
	printf '%s' "$payload" | env \
		-u MILLSTRAND_AGENT_ID -u MILLSTRAND_RUN_ID -u MILLSTRAND_RUN_REFERENCE \
		-u MILLSTRAND_CODEX_STRAND_BIN -u MILLSTRAND_CODEX_REQUEST_TIMEOUT \
		-u MILLSTRAND_CODEX_CONTEXT_MAX_BYTES \
		PLUGIN_ROOT="$plugin_root" \
		MILLSTRAND_CODEX_WORKSPACE="$workspace" \
		bash "$identity_hook"
}

invoke_discovered() {
	local payload=$1
	printf '%s' "$payload" | env \
		-u MILLSTRAND_AGENT_ID -u MILLSTRAND_RUN_ID -u MILLSTRAND_RUN_REFERENCE \
		-u MILLSTRAND_CODEX_STRAND_BIN -u MILLSTRAND_CODEX_REQUEST_TIMEOUT \
		-u MILLSTRAND_CODEX_CONTEXT_MAX_BYTES \
		-u MILLSTRAND_CODEX_WORKSPACE -u MILLSTRAND_WORKSPACE \
		PLUGIN_ROOT="$plugin_root" \
		bash "$identity_hook"
}

identity_from_output() {
	jq -er '.hookSpecificOutput.additionalContext
		| capture("^Your Millstrand identity is (?<identity>[a-z]+-[a-z]+-[a-z]+)\\.").identity'
}

parent_payload=$(session_payload "live-parent" "startup")
parent_output=$(invoke_explicit "$parent_payload")
parent_identity=$(identity_from_output <<<"$parent_output")

recovered_output=$(invoke_explicit "$(session_payload "live-parent" "resume")")
recovered_identity=$(identity_from_output <<<"$recovered_output")
[[ "$recovered_identity" == "$parent_identity" ]] || {
	echo "native binding recovery returned a different identity" >&2
	exit 1
}
for reconstruction_source in clear compact; do
	reconstructed_output=$(invoke_explicit "$(session_payload "live-parent" "$reconstruction_source")")
	reconstructed_identity=$(identity_from_output <<<"$reconstructed_output")
	[[ "$reconstructed_identity" == "$parent_identity" ]] || {
		echo "$reconstruction_source reconstruction returned a different identity" >&2
		exit 1
	}
done

discovered_output=$(invoke_discovered "$(session_payload "live-discovery" "startup" "$project/nested/cwd")")
discovered_identity=$(identity_from_output <<<"$discovered_output")
linked_output=$(invoke_discovered "$(session_payload "live-linked-discovery" "startup" "$linked_project/nested/cwd")")
linked_identity=$(identity_from_output <<<"$linked_output")

child_payload=$(subagent_payload "live-parent" "live-child")
child_output=$(invoke_explicit "$child_payload")
child_identity=$(identity_from_output <<<"$child_output")
[[ "$child_identity" != "$parent_identity" ]] || {
	echo "child startup reused its parent identity" >&2
	exit 1
}

cat >"$tmp_root/parent-probe.clj" <<EOF
(do
  (require '[millhouse.spools.identity :as identity]
           '[millstrand.api.current.alpha :as current]
           '[millstrand.api.graph.alpha :as graph])
  (let [rt (current/runtime)
        parent (identity/current rt "$parent_identity")
        child (identity/current rt "$child_identity")
        edges (graph/outgoing-edges rt [(:id parent)] "parent-of")]
    (if (some #(and (= (:from_strand_id %) (:id parent))
                    (= (:to_strand_id %) (:id child)))
              edges)
      "parent-ok"
      "parent-missing")))
EOF
parent_probe=$(mill weaver repl --stdin --workspace "$workspace" <"$tmp_root/parent-probe.clj" | sed -n '1p')
jq -e '. == "parent-ok"' <<<"$parent_probe" >/dev/null || {
	echo "live API did not persist the parent-of edge: $parent_probe" >&2
	exit 1
}

cat >"$tmp_root/conflict-seed.clj" <<'EOF'
(do
  (require '[millstrand.api.current.alpha :as current]
           '[millstrand.api.weaver.alpha :as weaver])
  (weaver/add! (current/runtime)
               {:title "forced-conflict-identity"
                :attributes {:identity/session "true"
                             :identity/id "forced-conflict-identity"
                             :identity/harness "codex"
                             :identity/native-session-id "live-parent"}})
  "conflict-seeded")
EOF
conflict_seed=$(mill weaver repl --stdin --workspace "$workspace" <"$tmp_root/conflict-seed.clj" | sed -n '1p')
jq -e '. == "conflict-seeded"' <<<"$conflict_seed" >/dev/null
conflict_output=$(invoke_explicit "$parent_payload")
jq -e '
  .continue == false and
  (.hookSpecificOutput | not) and
  (.systemMessage | test("failed|resolve|bound|conflict"; "i"))
' <<<"$conflict_output" >/dev/null || {
	echo "expected a bounded native-binding conflict: $conflict_output" >&2
	exit 1
}

mill weaver stop --workspace "${workspace:?}" >/dev/null
weaver_started=0
unavailable_output=$(invoke_explicit "$(session_payload "live-unavailable" "startup")")
jq -e '
  .continue == false and
  (.hookSpecificOutput | not) and
  (.systemMessage | test("failed|unavailable|not running|connect"; "i"))
' <<<"$unavailable_output" >/dev/null || {
	echo "expected a bounded unavailable-runtime result: $unavailable_output" >&2
	exit 1
}

printf '%s\n' \
	"Codex identity live acceptance passed (production identity.sh; Millhouse $identity_sha)." \
	"  identity=$parent_identity recovered=$recovered_identity" \
	"  discovered-subdir=$discovered_identity linked-worktree=$linked_identity" \
	"  child=$child_identity parent-edge=verified" \
	"  negatives=native-binding-conflict,unavailable-runtime"

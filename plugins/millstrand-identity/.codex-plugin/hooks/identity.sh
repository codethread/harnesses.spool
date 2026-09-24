#!/usr/bin/env bash
# Bind Codex native sessions to Millstrand identities and return the canonical
# identity instruction as Codex developer context.
set -u

context_max_bytes=3072
request_timeout=3s

static_warning() {
	printf '%s\n' '{"continue":true,"systemMessage":"Millstrand identity startup failed before binding; this session is unbound."}'
}

if ! command -v jq >/dev/null 2>&1; then
	static_warning
	exit 0
fi

warning() {
	local message=$1
	jq -cn --arg message "$message" '{continue: true, systemMessage: $message}'
}

managed_failure() {
	local message=$1
	jq -cn --arg message "$message" '{continue: false, stopReason: $message, systemMessage: $message}'
}

script_dir=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd) || {
	managed_failure "Millstrand identity startup cannot resolve its packaged hook path; this session is unbound."
	exit 0
}
script_path="$script_dir/identity.sh"
source_probe="$script_dir/identity-sources.sh"
if [[ ! -x "$source_probe" ]]; then
	managed_failure "Millstrand identity startup cannot inspect configured injector sources; this session is unbound."
	exit 0
fi

payload=$(cat) || {
	managed_failure "Millstrand identity startup could not read the Codex hook payload; this session is unbound."
	exit 0
}

event_name=$(jq -er '.hook_event_name' <<<"$payload" 2>/dev/null) || {
	managed_failure "Millstrand identity startup received an invalid Codex hook payload; this session is unbound."
	exit 0
}

case "$event_name" in
	SessionStart)
		if ! jq -e '
			(type == "object") and
			(.hook_event_name == "SessionStart") and
			(.session_id | type == "string" and length > 0) and
			(.cwd | type == "string" and length > 0) and
			(.model | type == "string" and length > 0) and
			(.source == "startup" or .source == "resume" or .source == "clear" or .source == "compact")
		' >/dev/null 2>&1 <<<"$payload"; then
			managed_failure "Millstrand identity startup received an invalid SessionStart payload; this session is unbound."
			exit 0
		fi

		;;
	SubagentStart)
		if ! jq -e '
			(type == "object") and
			(.hook_event_name == "SubagentStart") and
			(.session_id | type == "string" and length > 0) and
			(.cwd | type == "string" and length > 0) and
			(.turn_id | type == "string" and length > 0) and
			(.agent_id | type == "string" and length > 0) and
			(.agent_type | type == "string" and length > 0) and
			(.model | type == "string" and length > 0) and
			(has("source") | not)
		' >/dev/null 2>&1 <<<"$payload"; then
			managed_failure "Millstrand identity startup received an invalid SubagentStart payload; this child is unbound."
			exit 0
		fi
		;;
	*)
		managed_failure "Millstrand identity startup received an unsupported Codex hook event; this session is unbound."
		exit 0
		;;
esac

session_id=$(jq -er '.session_id' <<<"$payload")
cwd=$(jq -er '.cwd' <<<"$payload")
model=$(jq -er '.model' <<<"$payload")
source=$(jq -er '.source // "child"' <<<"$payload")
agent_id=$(jq -er '.agent_id // "root"' <<<"$payload")

# Project routing is authoritative; inherited workspace/identity hints never
# activate the adapter outside the launch project.
workspace=
common_dir=$(git -C "$cwd" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || common_dir=
if [[ -n "$common_dir" && "$(basename "$common_dir")" == .git ]]; then
	workspace="$(dirname "$common_dir")/.millstrand"
fi
[[ -n "$workspace" && -d "$workspace" ]] || exit 0
workspace=$(cd -P "$workspace" && pwd) || exit 1

managed_runtime_failure() {
	local code=$1
	local diagnostic=$2
	managed_failure "$diagnostic Native startup did not complete."
}

if [[ ${1:-} != --configured-source && ${1:-} != --locked ]]; then
	source_result=$(bash "$source_probe" "$event_name" "$cwd" 2>&1)
	source_status=$?
	if ((source_status != 0)); then
		source_diagnostic=$(LC_ALL=C printf '%s' "$source_result" | head -c 160 | tr '\n\r\t' '   ')
		managed_runtime_failure "unverifiable-profile" "Millstrand could not inspect Codex hook configuration: $source_diagnostic."
		exit 0
	fi
	if ! jq -e '
		(type == "object") and
		(keys == ["configured"]) and
		((.configured | type) == "number") and
		(.configured == (.configured | floor)) and
		(.configured >= 0)
	' >/dev/null 2>&1 <<<"$source_result"; then
		managed_runtime_failure "unverifiable-profile" "Millstrand identity startup received an invalid configured-source result."
		exit 0
	fi
	configured_sources=$(jq -er '.configured' <<<"$source_result")
	if ((configured_sources != 1)); then
		managed_runtime_failure "duplicate-injector" "Duplicate Millstrand identity injector configuration detected ($configured_sources configured sources); context was not injected."
		exit 0
	fi
	printf '%s' "$payload" | bash "$script_path" --configured-source
	exit 0
fi

lock_root=${XDG_RUNTIME_DIR:-${XDG_STATE_HOME:-${HOME:-}/.local/state}}/codex-millstrand-identity
lock_key=$(jq -nr \
	--arg event "$event_name" \
	--arg session "$session_id" \
	--arg source "$source" \
	--arg agent "$agent_id" \
	'[$event, $session, $source, $agent] | @base64 | gsub("="; "") | gsub("\\+"; "-") | gsub("/"; "_")')
lock_file="$lock_root/$lock_key.lock"
if ! mkdir -p "$lock_root" 2>/dev/null; then
	managed_runtime_failure "probe-failed" "Millstrand identity startup could not establish duplicate-injector protection."
	exit 0
fi

if [[ ${1:-} != --locked ]]; then
	lock_status=0
	if command -v lockf >/dev/null 2>&1; then
		printf '%s' "$payload" | lockf -k -s -t 0 "$lock_file" \
			bash "$script_path" --locked || lock_status=$?
	elif command -v flock >/dev/null 2>&1; then
		printf '%s' "$payload" | flock -n "$lock_file" \
			bash "$script_path" --locked || lock_status=$?
	else
		managed_runtime_failure "probe-failed" "Millstrand identity startup requires lockf or flock for crash-safe duplicate protection."
		exit 0
	fi
	if ((lock_status == 0)); then
		exit 0
	fi
	if ((lock_status == 75 || lock_status == 1)); then
		managed_runtime_failure "duplicate-injector" "Duplicate Millstrand identity injector detected for this Codex event; duplicate context was not injected."
	else
		managed_runtime_failure "probe-failed" "Millstrand identity lock execution failed (exit $lock_status)."
	fi
	exit 0
fi

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/codex-millstrand-identity.XXXXXX") || {
	managed_failure "Millstrand identity startup could not allocate bounded response storage; this session is unbound."
	exit 0
}
strand_pid=
cleanup() {
	if [[ -n "$strand_pid" ]]; then
		kill -TERM "$strand_pid" 2>/dev/null || true
		kill -KILL "$strand_pid" 2>/dev/null || true
		wait "$strand_pid" 2>/dev/null || true
	fi
	rm -rf "$work_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

strand_bin=${MILLSTRAND_CODEX_STRAND_BIN:-strand}
if [[ "$strand_bin" == */* ]]; then
	if [[ ! -x "$strand_bin" ]]; then
		managed_failure "Millstrand identity startup cannot execute Strand; this session is unbound."
		exit 0
	fi
elif ! command -v "$strand_bin" >/dev/null 2>&1; then
	managed_failure "Millstrand identity startup cannot find Strand; this session is unbound."
	exit 0
fi

last_stdout=
last_stderr=
last_status=0
call_strand() {
	local label=$1
	shift
	local stdout_file="$work_dir/$label.stdout"
	local stderr_file="$work_dir/$label.stderr"
	local -a scrubbed_names=(
		MILLSTRAND_AGENT_ID
		MILLSTRAND_RUN_ID
		MILLSTRAND_RUN_REFERENCE
		MILLSTRAND_MANAGED_BOOTSTRAP
		MILLSTRAND_MANAGED_GUIDANCE
		MILLSTRAND_WORKSPACE
	)
	local inherited_name
	while IFS= read -r inherited_name; do
		if [[ "$inherited_name" == MILLSTRAND_BOOTSTRAP_* ||
			"$inherited_name" == *_RESERVATION_ID ||
			"$inherited_name" == *_IDENTITY_TRANSPORT ]]; then
			scrubbed_names+=("$inherited_name")
		fi
	done < <(compgen -e)
	local -a command=(env)
	for inherited_name in "${scrubbed_names[@]}"; do
		command+=(-u "$inherited_name")
	done
	command+=("$strand_bin")
	if [[ -n "$workspace" ]]; then
		command+=(--workspace "$workspace")
	fi
	command+=(--cwd "$cwd" --timeout "$request_timeout" "$@")

	# Bound a misbehaving client before Codex's own hook timeout is reached.
	ulimit -f 128
	set +e
	{
		"${command[@]}" >"$stdout_file" 2>"$stderr_file" &
		strand_pid=$!
		wait "$strand_pid"
		last_status=$?
		strand_pid=
	} 2>>"$stderr_file"
	set -e
	last_stdout=$stdout_file
	last_stderr=$stderr_file
}

bounded_diagnostic() {
	LC_ALL=C head -c 80 "$last_stderr" 2>/dev/null | tr '\n\r\t' '   '
}

validate_startup_response() {
	jq -es '
		select(length == 1) | .[0] |
		select(
			(type == "object") and
			(.operation == "identity startup" or
             (.operation == "agent native-startup" and
              (."run-id" | type == "string" and length > 0) and
              (."observed-effort" | type == "string" and length > 0))) and
			(.identity | type == "string" and length > 0) and
			(."strand-id" | type == "string" and length > 0) and
			(.result == "minted" or .result == "recovered" or .result == "attached") and
			(.instruction == (
				"Your Millstrand identity is " + .identity +
				". Use it as `--owner " + .identity +
				"` for `kanban claim` and `--by-identity " + .identity +
				"` for Kanban notes, workflow mutations, and agent operations. " +
				"Keep `--identity` and `--parent-identity` for native-session references. " +
				"Inspect live help; never pass an unsupported flag or invent another identity."
			))
		)
	' "$1" 2>/dev/null
}

startup() {
	local label=$1
	local native_session_id=$2
	local parent_identity=${3:-}
	local -a args=(agent native-startup codex "$native_session_id" --model "$model")
	if [[ "$label" == parent ]]; then
		args=(identity startup codex "$native_session_id" --model "$model")
	elif [[ "$label" == root && -n "${MILLSTRAND_RUN_REFERENCE:-}" ]]; then
		args+=(--run-reference "$MILLSTRAND_RUN_REFERENCE")
	fi
	if [[ -n "$parent_identity" ]]; then
		args+=(--parent-identity "$parent_identity")
	fi
	call_strand "$label" "${args[@]}"
	if ((last_status != 0)); then
		managed_failure "Millstrand identity startup is unavailable ($label, exit $last_status): $(bounded_diagnostic). This session is unbound."
		return 1
	fi
	local bytes
	bytes=$(LC_ALL=C wc -c <"$last_stdout" | tr -d ' ')
	if ((bytes > context_max_bytes * 4)); then
		managed_failure "Millstrand identity startup returned an oversized response; required context was not injected and this session is unbound."
		return 1
	fi
	if ! validate_startup_response "$last_stdout" >/dev/null; then
		managed_failure "Millstrand identity startup returned an invalid response; required context was not injected and this session is unbound."
		return 1
	fi
	return 0
}

if [[ "$event_name" == "SessionStart" ]]; then
	startup root "$session_id" || exit 0
else
	# Parent identity is resolved independently from inherited launcher hints.
	startup parent "$session_id" || exit 0
	parent_identity=$(jq -ers '.[0].identity' "$last_stdout")
	child_key=$(jq -nr \
		--arg parent "$session_id" \
		--arg agent "$agent_id" \
		'def b64url: @base64 | gsub("="; "") | gsub("\\+"; "-") | gsub("/"; "_");
		 "codex-child:v1:" + ($parent | b64url) + ":" + ($agent | b64url)')
	startup child "$child_key" "$parent_identity" || exit 0
fi

context=$(jq -ers '.[0].instruction' "$last_stdout")
if [[ -n "$workspace" ]]; then
	workspace_json=$(jq -Rnr --arg workspace "$workspace" '$workspace | @json')
	context+=" Millstrand workspace: $workspace_json. Pass \`--workspace\` with that exact path on Strand commands."
else
	context+=" Run Strand from the Codex session working directory so workspace discovery is preserved."
fi

context_bytes=$(LC_ALL=C printf '%s' "$context" | wc -c | tr -d ' ')
if ((context_bytes > context_max_bytes)); then
	managed_failure "Millstrand identity context exceeds its reviewed byte budget; required context was not injected and this session is unbound."
	exit 0
fi

jq -cn \
	--arg event "$event_name" \
	--arg context "$context" \
	'{hookSpecificOutput: {hookEventName: $event, additionalContext: $context}}'

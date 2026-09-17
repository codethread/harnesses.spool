#!/usr/bin/env bash
# Ask Codex for its effective hook configuration and count identity injectors
# for one lifecycle event. This checks configured sources, not running processes.
set -u

event_name=${1:-}
cwd=${2:-}
case "$event_name" in
	SessionStart) codex_event=sessionStart ;;
	SubagentStart) codex_event=subagentStart ;;
	*)
		echo "unsupported hook event: $event_name" >&2
		exit 1
		;;
esac
[[ -n "$cwd" ]] || {
	echo "hook cwd is blank" >&2
	exit 1
}

for command in jq mkfifo ps; do
	command -v "$command" >/dev/null 2>&1 || {
		echo "required configuration probe command is unavailable: $command" >&2
		exit 1
	}
done

codex_bin=$(command -v codex 2>/dev/null || true)
ancestor_chain=
if [[ -z "$codex_bin" ]]; then
	ancestor=$PPID
	for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
		[[ "$ancestor" =~ ^[0-9]+$ ]] || break
		((ancestor > 1)) || break
		ancestor_command=$(ps -o comm= -p "$ancestor" 2>/dev/null | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
		ancestor_chain+=" ${ancestor}:${ancestor_command##*/}"
		case "${ancestor_command##*/}" in
			codex | codex-*)
				candidate=
				if [[ -e "/proc/$ancestor/exe" ]] && command -v readlink >/dev/null 2>&1; then
					candidate=$(readlink "/proc/$ancestor/exe" 2>/dev/null || true)
				elif command -v lsof >/dev/null 2>&1; then
					candidate=$(lsof -a -p "$ancestor" -d txt -Fn 2>/dev/null | sed -n 's/^n//p' | sed -n '1p')
				fi
				if [[ -z "$candidate" ]]; then
					ancestor_command_line=$(ps -o command= -p "$ancestor" 2>/dev/null | sed -e 's/^[[:space:]]*//')
					candidate=${ancestor_command_line%% *}
				fi
				if [[ -x "$candidate" ]]; then
					codex_bin=$candidate
					break
				fi
				;;
		esac
		ancestor=$(ps -o ppid= -p "$ancestor" 2>/dev/null | tr -d '[:space:]')
	done
fi
if [[ -z "$codex_bin" || ! -x "$codex_bin" ]]; then
	echo "Codex executable is unavailable from PATH and process ancestry:$ancestor_chain" >&2
	exit 1
fi

probe_dir=$(mktemp -d "${TMPDIR:-/tmp}/codex-identity-sources.XXXXXX") || exit 1
request_fifo="$probe_dir/request"
response_fifo="$probe_dir/response"
stderr_file="$probe_dir/stderr"
app_pid=
cleanup() {
	exec 8>&- 2>/dev/null || true
	exec 9<&- 2>/dev/null || true
	if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
		kill -TERM "$app_pid" 2>/dev/null || true
		kill -KILL "$app_pid" 2>/dev/null || true
		wait "$app_pid" 2>/dev/null || true
	fi
	rm -rf "$probe_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkfifo "$request_fifo" "$response_fifo" || exit 1
# Reaching this handler proves the invoking host's effective hooks feature is
# enabled. Replay that effective value because Codex CLI overrides such as
# `--enable hooks` are process-local and are otherwise lost by this probe.
env \
	-u MILLSTRAND_AGENT_ID \
	-u MILLSTRAND_RUN_ID \
	-u MILLSTRAND_RESERVATION_ID \
	-u MILLSTRAND_BOOTSTRAP_V1 \
	-u MILLSTRAND_MANAGED_BOOTSTRAP \
	-u MILLSTRAND_MANAGED_GUIDANCE \
	-u MILLSTRAND_IDENTITY_TRANSPORT \
	-u MILLSTRAND_WORKSPACE \
	"$codex_bin" -c features.hooks=true app-server --stdio <"$request_fifo" >"$response_fifo" 2>"$stderr_file" &
app_pid=$!
exec 8>"$request_fifo"
exec 9<"$response_fifo"

jq -cn '{id: 1, method: "initialize", params: {clientInfo: {name: "millstrand-identity-source-probe", version: "1"}, capabilities: {experimentalApi: true}}}' >&8

list_sent=0
deadline=$((SECONDS + 4))
while ((SECONDS < deadline)); do
	remaining=$((deadline - SECONDS))
	line=
	if ! IFS= read -r -t "$remaining" line <&9; then
		break
	fi
	[[ -n "$line" ]] || continue
	if jq -e '.id == 1' >/dev/null 2>&1 <<<"$line"; then
		if jq -e 'has("error")' >/dev/null 2>&1 <<<"$line"; then
			echo "Codex initialize failed" >&2
			exit 1
		fi
		if ((list_sent == 0)); then
			printf '%s\n' '{"method":"initialized"}' >&8
			jq -cn --arg cwd "$cwd" '{id: 2, method: "hooks/list", params: {cwds: [$cwd]}}' >&8
			list_sent=1
		fi
		continue
	fi
	if jq -e '.id == 2' >/dev/null 2>&1 <<<"$line"; then
		if jq -e 'has("error")' >/dev/null 2>&1 <<<"$line"; then
			echo "Codex hooks/list failed" >&2
			exit 1
		fi
		jq -cer --arg event "$codex_event" '
			[
				.result.data[]?.hooks[]?
				| select(.eventName == $event and .enabled == true)
				| select(
					(.command | type) == "string" and
					(.command | contains("/.codex-plugin/hooks/identity.sh"))
				)
			]
			| {configured: length}
		' <<<"$line"
		exit 0
	fi
done

stderr_bytes=$(LC_ALL=C wc -c <"$stderr_file" | tr -d ' ')
if ((stderr_bytes > 0)); then
	LC_ALL=C head -c 160 "$stderr_file" | tr '\n\r\t' '   ' >&2
else
	echo "Codex hooks/list timed out" >&2
fi
exit 1

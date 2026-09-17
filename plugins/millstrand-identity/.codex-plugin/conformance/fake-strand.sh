#!/usr/bin/env bash
# Deterministic Strand CLI double for the production Codex identity hook.
set -euo pipefail

workspace=
cwd=
timeout=
while (($# > 0)); do
	case "$1" in
		--workspace)
			workspace=$2
			shift 2
			;;
		--cwd)
			cwd=$2
			shift 2
			;;
		--timeout)
			timeout=$2
			shift 2
			;;
		*) break ;;
	esac
done

[[ -n "$cwd" && "$timeout" == "3s" ]]
[[ "${1:-}" == "identity" && "${2:-}" == "startup" && "${3:-}" == "codex" ]]
native_session_id=${4:?native session ID is required}
shift 4
model=
parent_identity=
while (($# > 0)); do
	case "$1" in
		--model)
			model=$2
			shift 2
			;;
		--parent-identity)
			parent_identity=$2
			shift 2
			;;
		*)
			printf 'unexpected fake Strand argument: %s\n' "$1" >&2
			exit 64
			;;
	esac
done
[[ -n "$model" ]]

managed_environment_present=false
while IFS= read -r name; do
	if [[ "$name" == MILLSTRAND_AGENT_ID ||
		"$name" == MILLSTRAND_RUN_ID ||
		"$name" == MILLSTRAND_RESERVATION_ID ||
		"$name" == MILLSTRAND_MANAGED_BOOTSTRAP ||
		"$name" == MILLSTRAND_MANAGED_GUIDANCE ||
		"$name" == MILLSTRAND_WORKSPACE ||
		"$name" == MILLSTRAND_BOOTSTRAP_* ||
		"$name" == *_RESERVATION_ID ||
		"$name" == *_IDENTITY_TRANSPORT ]]; then
		managed_environment_present=true
	fi
done < <(compgen -e)

case "$native_session_id" in
	codex-child:v1:*) identity=fixture-child-identity ;;
	*) identity=fixture-root-identity ;;
esac
instruction="Your Millstrand identity is $identity. Use $identity for identity-bearing operations; pass \`--by-identity $identity\` explicitly. Do not invent another identity."
result=${FAKE_STRAND_RESULT:-minted}
resolved_workspace=$workspace
if [[ -z "$resolved_workspace" ]]; then
	if [[ "$cwd" == *"linked-worktree"* ]]; then
		resolved_workspace=/workspace/project/.millstrand
	else
		resolved_workspace="$cwd/.millstrand"
	fi
fi

if [[ -n "${FAKE_STRAND_LOG:-}" ]]; then
	jq -cn \
		--arg workspace "$workspace" \
		--arg cwd "$cwd" \
		--arg timeout "$timeout" \
		--arg native_session_id "$native_session_id" \
		--arg model "$model" \
		--arg parent_identity "$parent_identity" \
		--argjson managed_environment_present "$managed_environment_present" \
		'{workspace: $workspace, cwd: $cwd, timeout: $timeout,
		  native_session_id: $native_session_id, model: $model,
		  parent_identity: $parent_identity,
		  managed_environment_present: $managed_environment_present}' \
		>>"$FAKE_STRAND_LOG"
fi

mode=${FAKE_STRAND_MODE:-success}
if [[ -n "${FAKE_STRAND_PID_FILE:-}" ]]; then
	printf '%s\n' "$$" >"$FAKE_STRAND_PID_FILE"
fi
if [[ "$mode" == "hold" ]]; then
	: >"${FAKE_STRAND_READY:?hold mode requires FAKE_STRAND_READY}"
	IFS= read -r _ <"${FAKE_STRAND_GATE:?hold mode requires FAKE_STRAND_GATE}"
	mode=success
fi

case "$mode" in
	success)
		jq -cn \
			--arg identity "$identity" \
			--arg instruction "$instruction" \
			--arg result "$result" \
			'{operation: "identity startup", identity: $identity, "strand-id": "fixture-strand", result: $result, instruction: $instruction}'
		;;
	oversized)
		instruction="Your Millstrand identity is $identity."
		for _ in {1..256}; do
			instruction+=" Required identity policy must remain complete."
		done
		jq -cn \
			--arg identity "$identity" \
			--arg instruction "$instruction" \
			'{operation: "identity startup", identity: $identity, "strand-id": "fixture-strand", result: "minted", instruction: $instruction}'
		;;
	failure | no-workspace | invalid-binding)
		case "$FAKE_STRAND_MODE" in
			no-workspace) diagnostic="no Millstrand workspace found from cwd" ;;
			invalid-binding) diagnostic="native session has conflicting identity bindings" ;;
			*) diagnostic="fake Strand unavailable; diagnostic payload must be bounded" ;;
		esac
		for _ in {1..64}; do
			printf '%s. ' "$diagnostic" >&2
		done
		printf '\n' >&2
		exit 70
		;;
	flood)
		while :; do
			printf 'fake Strand output flood must be bounded. '
		done
		;;
	hang)
		exec tail -f /dev/null
		;;
	invalid-json)
		printf '{not-json}\n'
		;;
	missing-context)
		printf '{"operation":"identity startup","identity":"fixture-root-identity","strand-id":"fixture-strand","result":"minted"}\n'
		;;
	empty-context)
		printf '{"operation":"identity startup","identity":"fixture-root-identity","strand-id":"fixture-strand","result":"minted","instruction":""}\n'
		;;
	multiple-responses)
		printf '{"operation":"identity startup","identity":"fixture-root-identity","strand-id":"fixture-strand","result":"minted","instruction":"ambiguous"}\n%.0s' {1..2}
		;;
	*)
		printf 'unknown FAKE_STRAND_MODE\n' >&2
		exit 64
		;;
esac

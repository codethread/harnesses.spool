#!/usr/bin/env bash
# sessionStart: if MILLSTRAND_HARNESS_CURSOR_SYS_PROMPT is set, return it as
# additional_context. Never block the session; never write non-JSON to stdout.
set -u

cat >/dev/null || true

prompt="${MILLSTRAND_HARNESS_CURSOR_SYS_PROMPT-}"
if [ -z "${prompt}" ]; then
  printf '%s\n' '{}'
  exit 0
fi

emit_context() {
  if command -v jq >/dev/null 2>&1; then
    jq -n --arg ctx "$prompt" '{additional_context: $ctx}'
    return
  fi
  python3 -c 'import json, os; print(json.dumps({"additional_context": os.environ["MILLSTRAND_HARNESS_CURSOR_SYS_PROMPT"]}))'
}

if ! emit_context; then
  printf '%s\n' '{}'
fi
exit 0

# Agents

This repo provides a Millstrand spool (a library for the millstrand ecosystem)

The spool provides agent harness capabilities to allow consumers to run different harnesses like claude code, cursor, codex etc, all via the `strand` cli (agent to agent) or directly in a tui via `./bin/agent` (user to agent)

This repo dogfoods its own spool in the millstrand config at `.millstrand/`.

## Working here

- Run `strand prime kanban`, claim a feature card, and use its recorded worktree.
- Never edit `main` or push directly to `main`; feature-branch pushes are expected.
- Inspect `strand workflow show land` and `strand prime merge-queue`, then drive
  shared `land` for quality, one basic review, FIFO merge, card completion, and
  branch/worktree cleanup.

## Rules

- **Never restart a running weaver** without explicit user sign-off.
- **Kill by PID only** — never `pkill -f <pattern>` (prompts can quote the pattern and strafe siblings).
- **Disposable workspaces for workspace-backed tests** (weaver-world fixtures, smoke config) — never the shared `.millstrand` world. Use `--workspace` from `mktemp -d`; guard with `${ws:?}`.

<!-- mill:millstrand-prime -->

## Millstrand / strand

This repo uses Millstrand strands to track work. Start with `strand --help`. Run `mill prime millstrand` when building on this repo's `.millstrand/` config, or working with millstrand spools, weaver or REPL.
<!-- /mill:millstrand-prime -->

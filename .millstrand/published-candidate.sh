#!/bin/sh
# Run from the candidate worktree root. land-quality.sh alone owns suite locking.
set -eu

contract=.millstrand/land-quality.sh
target=${1-}
die() {
  echo "published candidate: $*" >&2
  exit 1
}

marker=$(git rev-parse --git-path millstrand-land-quality-head)
# A failed retry must never retain evidence from an earlier attempt.
rm -f "$marker"
[ "$#" = 1 ] && [ -n "$target" ] || die "expected exactly one feature branch"
[ "$target" != main ] || die "main is not a published feature candidate"
git check-ref-format "refs/heads/$target" || die "invalid feature branch: $target"
head=$(git rev-parse HEAD)

verify_candidate() {
  branch=$(git branch --show-current)
  [ "$branch" = "$target" ] || die "checked-out branch is $branch; expected $target"
  actual=$(git rev-parse HEAD)
  [ "$actual" = "$head" ] || die "HEAD changed: expected $head, found $actual"
  status=$(git status --porcelain=v1 --untracked-files=all)
  [ -z "$status" ] || die "worktree is dirty: $status"
  git diff --quiet || die "unstaged changes are present"
  git diff --cached --quiet || die "staged changes are present"
  git fetch origin "+refs/heads/$target:refs/remotes/origin/$target" \
    || die "cannot refresh origin/$target"
  origin_head=$(git rev-parse "refs/remotes/origin/$target")
  [ "$origin_head" = "$head" ] \
    || die "unpushed or changed candidate: local $head, origin $origin_head"
}

verify_candidate
git cat-file -e "HEAD:$contract" >/dev/null 2>&1 \
  || die "quality contract $contract is not present at HEAD"
[ -f "$contract" ] && [ -x "$contract" ] \
  || die "quality contract $contract is unavailable or not executable"
export LAND_EXPECTED_BRANCH="$target"
export LAND_EXPECTED_HEAD="$head"
"$contract"
verify_candidate

# Shared Land's quality receipt remains an exact commit, never process success alone.
mkdir -p "$(dirname "$marker")"
printf '%s\n' "$head" >"$marker.$$"
mv -f "$marker.$$" "$marker"
printf '%s\n' "published candidate: passed at unchanged $target HEAD $head"

#!/usr/bin/env bash
# Opens one pull request per expired flag, removing the flag and its dead branch.
#
# Needs: git, gh (authenticated), java 17+, and the flag-expiry classes on the classpath.
# Env:   SRC_DIR   source root to scan          (default: src)
#        CLASSES   classpath with flag-expiry   (default: target/classes)
#        BASE      branch the PRs target        (default: main)
#        DRY_RUN   1 = only print what would happen
set -euo pipefail

SRC_DIR="${SRC_DIR:-src}"
CLASSES="${CLASSES:-target/classes}"
BASE="${BASE:-main}"
CLEANUP=(java -cp "$CLASSES" io.flagexpiry.cleanup.FlagCleanup)

git fetch --quiet origin "$BASE"

"${CLEANUP[@]}" list --src "$SRC_DIR" | while IFS=$'\t' read -r name owner expires note; do
  branch="flag-cleanup/$(echo "$name" | tr -c 'A-Za-z0-9._-\n' '-')"

  if git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
    echo "skip $name: $branch already exists"
    continue
  fi
  if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "would open PR for $name (owner $owner, expired $expires)"
    continue
  fi

  git checkout --quiet -B "$branch" "origin/$BASE"
  if ! "${CLEANUP[@]}" remove --src "$SRC_DIR" --flag "$name"; then
    echo "skip $name: needs manual cleanup"
    git checkout --quiet -- . && git checkout --quiet "$BASE"
    continue
  fi

  git add -A "$SRC_DIR"
  git commit --quiet -m "Remove expired flag '$name'" \
    -m "Owner: $owner. Expired: $expires. Kept the flag-on behaviour."
  git push --quiet -u origin "$branch"
  gh pr create --base "$BASE" --head "$branch" \
    --title "Remove expired flag '$name'" \
    --body "Flag \`$name\` expired on $expires.

- Owner: **$owner**
- Note: ${note:-none}

The flag annotation, its \`isOn\` checks and the dead (flag-off) branch were removed automatically; the **flag-on** behaviour is kept. Please check that this is the behaviour you shipped before merging."
  git checkout --quiet "$BASE"
done

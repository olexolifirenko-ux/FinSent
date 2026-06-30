#!/usr/bin/env bash
#
# FinSent prod rollback: repoint `current` at the previous release and restart.
# Shared state (.env, data/, logs/, run/) is untouched — only code reverts.
#
# Usage: ./rollback.sh            # roll back to the most recent prior release
#        ./rollback.sh <tag>      # roll back to a specific releases/<tag>
set -euo pipefail

PROD="${FINSENT_PROD:-/home/aiworker/finsent-prod}"
SERVICE="finsent.service"
cd "$PROD/releases"

CURRENT="$(basename "$(readlink "$PROD/current" 2>/dev/null || echo none)")"

if [ "${1:-}" != "" ]; then
    TARGET="$1"
else
    TARGET="$(ls -1dt */ 2>/dev/null | sed 's#/##' | grep -v "^${CURRENT}$" | head -1 || true)"
fi

[ -n "${TARGET:-}" ]      || { echo "[rollback] no previous release to roll back to." >&2; exit 1; }
[ -d "$PROD/releases/$TARGET" ] || { echo "[rollback] release '$TARGET' not found." >&2; exit 1; }

echo "[rollback] $CURRENT -> $TARGET"
ln -sfn "$PROD/releases/$TARGET" "$PROD/current"
systemctl --user restart "$SERVICE"
sleep 3
systemctl --user is-active "$SERVICE" >/dev/null && echo "[rollback] service active on $TARGET" \
    || { echo "[rollback] ERROR: service failed to start on $TARGET" >&2; exit 1; }

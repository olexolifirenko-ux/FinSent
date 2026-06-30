#!/usr/bin/env bash
#
# FinSent prod deploy: build the current dev commit, package release/ minus state,
# publish as an immutable timestamped release, flip `current`, restart the service.
#
# State (.env, data/, logs/, run/) lives in shared/ and is symlinked INTO each
# release, so deploys replace code only and never touch live state. Rollback with
# rollback.sh (just repoints `current`).
#
# Usage: ./deploy.sh            # build & deploy HEAD of the dev tree
#        FINSENT_DEV=... FINSENT_PROD=... FINSENT_JDK=... ./deploy.sh
set -euo pipefail

DEV="${FINSENT_DEV:-/home/aiworker/.openclaw/workspace/FinSent}"
PROD="${FINSENT_PROD:-/home/aiworker/finsent-prod}"
JDK="${FINSENT_JDK:-/home/aiworker/tools/java/jdk-17.0.15+6}"
SERVICE="finsent.service"
KEEP=5   # how many old releases to retain

log() { printf '[deploy] %s\n' "$*"; }

[ -d "$DEV/.git" ] || { echo "[deploy] ERROR: dev tree not found at $DEV" >&2; exit 1; }
[ -d "$JDK" ]      || { echo "[deploy] ERROR: JDK not found at $JDK" >&2; exit 1; }

cd "$DEV"
if [ -n "$(git status --porcelain)" ]; then
    log "WARNING: dev tree has uncommitted changes — prod should run a committed build."
fi
SHA="$(git rev-parse --short HEAD)"
STAMP="$(date +%Y-%m-%d_%H%M%S)"
TAG="${STAMP}_${SHA}"
DEST="$PROD/releases/$TAG"

log "building deployRelease at $SHA (JAVA_HOME=$JDK) ..."
JAVA_HOME="$JDK" ./gradlew --quiet deployRelease

log "packaging -> $DEST (excluding live state)"
mkdir -p "$DEST"
rsync -a --delete \
    --exclude='.env' --exclude='data' --exclude='logs' --exclude='run' \
    --exclude='common/linux64/jre' \
    "$DEV/release/" "$DEST/"

log "wiring shared state + bundled JRE into the release"
ln -sfn "$PROD/shared/.env"  "$DEST/.env"
ln -sfn "$PROD/shared/data"  "$DEST/data"
ln -sfn "$PROD/shared/logs"  "$DEST/logs"
ln -sfn "$PROD/shared/run"   "$DEST/run"
mkdir -p "$DEST/common/linux64"
ln -sfn "$JDK" "$DEST/common/linux64/jre"

log "flipping current -> $TAG"
ln -sfn "$DEST" "$PROD/current"

if systemctl --user list-unit-files "$SERVICE" >/dev/null 2>&1; then
    log "restarting $SERVICE"
    systemctl --user restart "$SERVICE"
    sleep 3
    systemctl --user is-active "$SERVICE" >/dev/null && log "service active" \
        || { echo "[deploy] ERROR: service failed to come up; check 'journalctl --user -u $SERVICE'" >&2; exit 1; }
else
    log "NOTE: $SERVICE not installed yet — skipping restart (install the unit, then run again)"
fi

log "pruning old releases (keeping last $KEEP)"
cd "$PROD/releases"
ls -1dt */ 2>/dev/null | tail -n +$((KEEP+1)) | sed 's#/##' | while read -r old; do
    log "  removing $old"; rm -rf -- "$old"
done

log "done: $TAG"

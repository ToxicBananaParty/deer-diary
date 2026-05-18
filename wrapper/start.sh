#!/usr/bin/env bash
# Pterodactyl startup wrapper for the Deer Diary server.
#
# Replaces the default `./run.sh` startup with: check for a pack update,
# apply it if approved, then hand off to run.sh. Approval gate means
# published updates don't auto-apply on restart — you have to drop the new
# version string into approved-version.txt first (or run
# `prism_sync server-approve <version>` from your local shell).
#
# Wire this up in the Pterodactyl panel: Startup -> Startup Command =
#   bash /home/container/wrapper/start.sh
# Leave the rest of the Java args / runtime config as-is.

set -euo pipefail
cd /home/container

PACK_URL="https://toxicbananaparty.github.io/deer-diary/packwiz-server/pack.toml"
APPLIED_VERSION_FILE="applied-version.txt"
APPROVED_VERSION_FILE="approved-version.txt"
BOOTSTRAP_JAR="wrapper/packwiz-installer-bootstrap.jar"

# Fetch the published pack version. `version = "YYYY.MM.DD..."` is the first
# (or only) `version = ` line in pack.toml; grep+sed extracts it.
remote_version="$(
    curl -fsSL --max-time 15 "$PACK_URL" \
        | grep -E '^version = ' \
        | head -1 \
        | sed -E "s/^version = ['\"]([^'\"]*)['\"]\$/\1/"
)"
if [[ -z "$remote_version" ]]; then
    echo "[wrapper] WARN: could not read version from $PACK_URL; running with whatever is on disk"
else
    applied_version="$(cat "$APPLIED_VERSION_FILE" 2>/dev/null || echo '')"

    if [[ "$remote_version" != "$applied_version" ]]; then
        approved_version="$(cat "$APPROVED_VERSION_FILE" 2>/dev/null || echo '')"
        if [[ "$approved_version" == "$remote_version" ]]; then
            echo "[wrapper] applying pack update: $applied_version -> $remote_version"
            java -jar "$BOOTSTRAP_JAR" -s server -g "$PACK_URL"
            echo "$remote_version" > "$APPLIED_VERSION_FILE"
            # Consume the approval so it doesn't auto-apply the next time
            # this same version's pack.toml gets re-served (e.g. server
            # restart between publishes).
            rm -f "$APPROVED_VERSION_FILE"
        else
            echo "[wrapper] update $remote_version is published but NOT APPROVED."
            echo "[wrapper] Running with $applied_version. To approve, run locally:"
            echo "[wrapper]   prism_sync server-approve $remote_version"
            echo "[wrapper] (or write '$remote_version' to /home/container/$APPROVED_VERSION_FILE)"
            echo "[wrapper] then restart the server."
        fi
    fi
fi

# Hand off to NeoForge's installed run.sh. Pterodactyl propagates JVM args
# via environment, which run.sh respects.
exec ./run.sh

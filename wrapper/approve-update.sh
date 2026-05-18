#!/usr/bin/env bash
# Convenience: approve a pending pack update from inside the BloomHost
# container. Run from the Pterodactyl web console or via SSH.
#
# Usage: bash /home/container/wrapper/approve-update.sh <version>
#
# Equivalent to running `prism_sync server-approve <version>` from your
# local shell — both just write the version string to approved-version.txt.

set -euo pipefail
if [[ -z "${1:-}" ]]; then
    echo "Usage: $0 <version>"
    echo "  e.g. $0 2026.05.18"
    exit 1
fi
echo "$1" > /home/container/approved-version.txt
echo "Approved version $1. Restart the server to apply."

#!/usr/bin/env bash
#
# Run the official Jakarta Persistence 3.2 TCK runner.
# The TCK runner is OUT of the reactor, so we build it directly (no -pl).
#
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

echo "Running official Jakarta Persistence 3.2 TCK..."

"$ROOT/scripts/build.sh" -pl "$HERE" test -Ptck-run

echo "Completed running Jakarta Persistence 3.2 TCK"

# Optional: Run with PostgreSQL profile if you want to use PostgreSQL instead of H2
# "$ROOT/scripts/build.sh" -pl "$HERE" test -Ptck-run,tck-pg

# Optional: Run with signature tests profile
# "$ROOT/scripts/build.sh" -pl "$HERE" test -Ptck-run,tck-sig
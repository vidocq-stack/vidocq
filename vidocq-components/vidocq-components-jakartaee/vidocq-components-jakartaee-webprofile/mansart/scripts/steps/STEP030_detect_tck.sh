#!/usr/bin/env bash
# STEP 030 — is there a runnable TCK for THIS spec at THIS version?
# in:  <XXX>           out: verdict on stdout. exit 0 = runnable, 1 = not.
# Counts test classes inside the candidate jars: names lie (persistence-tck is a
# POM with no jar, -dist is an 8 KB stub, only -spec-tests holds the 161 tests).
# exit 1 is INFORMATION, not a failure: it means run STEP040 next.
set -uo pipefail
H="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[ $# -ge 1 ] || { echo "usage: $(basename "$0") <XXX>"; exit 2; }
M="$H/docs/spec-src/$1/spec-meta.json"
[ -f "$M" ] || { echo "no $M — run STEP010 first"; exit 3; }
read -r KW VER < <(python3 -c "
import json;t=json.load(open('$M')).get('tck') or {}
print(t.get('keyword') or '$1'.lower(), t.get('spec_version') or '')")
echo "== STEP030 detect TCK: $KW ${VER:-(no version in url)}"
python3 "$H/scripts/tck-find.py" "$KW" ${VER:+--spec-version "$VER"}; rc=$?
[ $rc -eq 0 ] && echo "next: STEP050_module_path.sh $1" || echo "next: STEP040_install_tck.sh $1"
exit $rc

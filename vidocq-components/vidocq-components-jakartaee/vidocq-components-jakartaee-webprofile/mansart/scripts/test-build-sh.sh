#!/usr/bin/env bash
# Behaviour contract for scripts/build.sh. Run it before trusting the script.
# Written first, on purpose: build.sh exists to fix a bug that silently reported
# 262 green Maven runs while 38 had failed.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$HERE/build.sh"
pass=0; fail=0
ok()   { pass=$((pass+1)); printf '  PASS %s\n' "$1"; }
ko()   { fail=$((fail+1)); printf '  FAIL %s — %s\n' "$1" "${2:-}"; }

# 1. exists and is executable
[ -x "$BUILD" ] && ok "build.sh is executable" || ko "build.sh is executable"

# 2. success propagates exit 0
out=$("$BUILD" --version 2>&1); rc=$?
[ $rc -eq 0 ] && ok "success returns 0" || ko "success returns 0" "got $rc"

# 3. output stays short — the whole point is to keep it out of the context
lines=$(printf '%s\n' "$out" | wc -l | tr -d ' ')
[ "$lines" -le 12 ] && ok "success output <= 12 lines ($lines)" \
                    || ko "success output <= 12 lines" "got $lines"

# 4. THE bug: a failing Maven must NOT return 0, even though the script pipes
out=$("$BUILD" this-goal-does-not-exist 2>&1); rc=$?
[ $rc -ne 0 ] && ok "failure returns non-zero ($rc)" \
              || ko "failure returns non-zero" "got 0 — the pipefail bug is back"

# 5. failure output stays short too
lines=$(printf '%s\n' "$out" | wc -l | tr -d ' ')
[ "$lines" -le 25 ] && ok "failure output <= 25 lines ($lines)" \
                    || ko "failure output <= 25 lines" "got $lines"

# 6. the full log is written somewhere retrievable
printf '%s\n' "$out" | grep -qE 'log: .+\.log' && ok "failure names a log file" \
                                               || ko "failure names a log file"
logf=$(printf '%s\n' "$out" | grep -oE '/[^ ]+\.log' | tail -1)
[ -n "${logf:-}" ] && [ -s "$logf" ] && ok "log file exists and is non-empty" \
                                     || ko "log file exists and is non-empty" "${logf:-none}"

# 7. never leaks the whole log to stdout
[ "$(wc -c <<<"$out")" -lt 4000 ] && ok "output under 4 kB" \
                                  || ko "output under 4 kB" "$(wc -c <<<"$out") bytes"


# 8. the JDK is pinned from .sdkmanrc, not inherited from the caller's shell
#    (a harness run reported Java 26 while .sdkmanrc pins 25 — a silently wrong
#    compiler is worse than a failed build)
want=$(grep -oE '^java=.*' "$HERE/../.sdkmanrc" | cut -d= -f2)
out=$(JAVA_HOME=/nonexistent "$BUILD" --version 2>&1); rc=$?
[ $rc -eq 0 ] && ok "runs with a broken caller JAVA_HOME" || ko "runs with a broken caller JAVA_HOME" "got $rc"
printf '%s\n' "$out" | grep -q "jdk: ${want}" && ok "reports the pinned jdk ($want)" \
                                              || ko "reports the pinned jdk ($want)" "$(printf '%s' "$out" | head -3)"

printf 'RESULT pass=%d fail=%d\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# Tests of install-zulu-crac.sh: the JDK is extracted only once its archive matches the pinned SHA-256, a failed or
# truncated download is tried again, and the archive is the one of the machine's architecture.
# Offline: ZULU_DIST_BASE points at a file:// directory, and a curl on the PATH can cut the first downloads short, as
# cdn.azul.com did in crac.yml run 3034.
# Run: ./install-zulu-crac-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == *"$expected"* ]]; then
    echo "ok   $name"
  else
    echo "FAIL $name: expected '$expected' in '$actual'"
    failures=$((failures + 1))
  fi
}

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }

# A JDK archive as Azul lays it out: one top directory, bin/java inside.
DIST="$WORK/dist"
NAME=zulu-test-linux_x64
mkdir -p "$DIST" "$WORK/src/$NAME/bin"
printf '#!/bin/sh\necho "openjdk version test-crac" >&2\n' > "$WORK/src/$NAME/bin/java"
chmod +x "$WORK/src/$NAME/bin/java"
head -c 200000 /dev/urandom > "$WORK/src/$NAME/lib.bin" # big enough for a cut to land inside the archive
tar -czf "$DIST/$NAME.tar.gz" -C "$WORK/src" "$NAME"
PIN=$(sha256 "$DIST/$NAME.tar.gz")

# A curl that cuts the first <n> archive downloads short (a truncated body, exit 0) and passes everything else on.
STUB="$WORK/stub"
mkdir -p "$STUB"
REAL_CURL="$(command -v curl)"
cat > "$STUB/curl" <<EOF
#!/usr/bin/env bash
out=""; args=("\$@")
for ((i = 0; i < \${#args[@]}; i++)); do [ "\${args[i]}" = -o ] && out="\${args[i+1]}"; done
count="\$(cat "$WORK/cuts" 2>/dev/null || echo 0)"
if [ "\$count" -gt 0 ] && [ -n "\$out" ]; then
  echo \$((count - 1)) > "$WORK/cuts"
  "$REAL_CURL" "\$@" || exit \$?
  head -c 1000 "\$out" > "\$out.cut" && mv "\$out.cut" "\$out"
  exit 0
fi
exec "$REAL_CURL" "\$@"
EOF
chmod +x "$STUB/curl"

# run <cuts> [VAR=value...] -> the script's output, exit status and the java it left, as "<out>|<rc>|<java>"
run() {
  local cuts="$1" dest out rc
  shift
  dest="$WORK/dest.$RANDOM"
  echo "$cuts" > "$WORK/cuts"
  out=$(env -i PATH="$STUB:$PATH" HOME="$HOME" ZULU_DIST_BASE="file://$DIST" ZULU_RETRY_DELAY=0 "$@" \
      bash "$HERE/install-zulu-crac.sh" "$dest" 2>&1); rc=$?
  echo "$out|$rc|$( [ -x "$dest/bin/java" ] && "$dest/bin/java" 2>&1 || echo no-java)"
}
# The test archive and its hash in place of the pinned ones.
TEST=(ZULU_CRAC_ARCHIVE="$NAME.tar.gz" ZULU_CRAC_SHA256="$PIN")

check "a matching archive is extracted, its top directory stripped" "|0|openjdk version test-crac" "$(run 0 "${TEST[@]}")"
check "a cut download is tried again"                 "|0|openjdk version test-crac" "$(run 1 "${TEST[@]}")"
check "the cut is reported"                           "attempt 1 of 4: the SHA-256 does not match" "$(run 1 "${TEST[@]}")"
check "three cuts in a row still end well"            "|0|openjdk version test-crac" "$(run 3 "${TEST[@]}")"
check "four cuts: refused, nothing extracted"         "|1|no-java"                   "$(run 4 "${TEST[@]}")"
check "the refusal names the archive"                 "no $NAME.tar.gz matching its pinned SHA-256 after 4 attempts" "$(run 4 "${TEST[@]}")"
check "another archive under the pinned name: refused" "|1|no-java" \
  "$(run 0 ZULU_CRAC_ARCHIVE="$NAME.tar.gz" ZULU_CRAC_SHA256="$(printf '0%.0s' {1..64})")"
check "a pin that is not a SHA-256: refused"          "is not a SHA-256" \
  "$(run 0 ZULU_CRAC_ARCHIVE="$NAME.tar.gz" ZULU_CRAC_SHA256=abc)"

# The pinned archives, by machine: the download names the archive of that architecture (absent here, so refused).
pinned=zulu25.36.205-ca-crac-jdk25.0.4.1
check "x86_64 takes the x64 archive"      "cannot download file://$DIST/$pinned-linux_x64.tar.gz"     "$(run 0 ZULU_MACHINE=x86_64)"
check "aarch64 takes the aarch64 archive" "cannot download file://$DIST/$pinned-linux_aarch64.tar.gz" "$(run 0 ZULU_MACHINE=aarch64)"
check "arm64 takes the aarch64 archive"   "cannot download file://$DIST/$pinned-linux_aarch64.tar.gz" "$(run 0 ZULU_MACHINE=arm64)"
check "another machine: refused"          "No Azul Zulu CRaC build for ppc64le" "$(run 0 ZULU_MACHINE=ppc64le)"
check "another machine downloads nothing" "|2|no-java"                          "$(run 0 ZULU_MACHINE=ppc64le)"

if [ "$failures" -ne 0 ]; then
  echo "$failures test(s) failed"
  exit 1
fi
echo "all install-zulu-crac tests passed"

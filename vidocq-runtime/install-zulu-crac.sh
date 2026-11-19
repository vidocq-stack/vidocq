#!/usr/bin/env bash
# Installs Azul Zulu CRaC, the JDK the CRaC extension builds and runs on, in <dest-dir> (its bin/java directly
# there), for the machine's architecture. Used by ci.yml (the extension's deploy pass) and crac.yml (the nightly).
#
#   install-zulu-crac.sh <dest-dir>
#
# The archive is extracted only once it matches the SHA-256 pinned below; a failed or truncated download is tried
# again, four attempts in all. crac.yml run 3034 died on a download from cdn.azul.com cut short after 3 min 24 s,
# piped straight into tar. Azul publishes no checksum for this build (no .sha256 on the CDN, and its metadata API
# does not list it): the pins are the hashes of the archives downloaded on 2026-11-05, at the sizes the CDN
# announces, gzip-tested. A new build means new pins, taken the same way.
#
#   ZULU_DIST_BASE    where the archives are, https://cdn.azul.com/zulu/bin by default
#   ZULU_RETRY_DELAY  seconds to wait after a failed attempt, times its number, 10 by default
#   ZULU_MACHINE, ZULU_CRAC_ARCHIVE, ZULU_CRAC_SHA256: the tests' overrides (install-zulu-crac-test.sh); the
#   workflows set none of them.
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: install-zulu-crac.sh <dest-dir>" >&2; exit 2; }
dest=$1

ZULU_CRAC=zulu25.36.205-ca-crac-jdk25.0.4.1
machine=${ZULU_MACHINE:-$(uname -m)}
# Azul names its archives x64 and aarch64.
case "$machine" in
  x86_64)        arch=x64;     pin=6d1beee9795c544f53ff7e665d6455081960d18b476c0f6054f5e35cef97b61e ;;
  aarch64|arm64) arch=aarch64; pin=cec6bb580db329e9616b63294221d4d817a4b3921d2bc1d092ff3c5ba471de7e ;;
  *) echo "No Azul Zulu CRaC build for $machine" >&2; exit 2 ;;
esac
name=${ZULU_CRAC_ARCHIVE:-$ZULU_CRAC-linux_$arch.tar.gz}
expected=${ZULU_CRAC_SHA256:-$pin}
[[ $expected =~ ^[0-9a-f]{64}$ ]] || { echo "install-zulu-crac.sh: '$expected' is not a SHA-256" >&2; exit 2; }

attempts=4
delay=${ZULU_RETRY_DELAY:-10}
url="${ZULU_DIST_BASE:-https://cdn.azul.com/zulu/bin}/$name"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }

for ((i = 1; i <= attempts; i++)); do
  if ! curl -fsSL --connect-timeout 20 --max-time 900 -o "$tmp/$name" "$url"; then
    echo "attempt $i of $attempts: cannot download $url" >&2
  elif [ "$(sha256 "$tmp/$name")" != "$expected" ]; then
    echo "attempt $i of $attempts: the SHA-256 does not match, the download was cut short or altered" >&2
  else
    mkdir -p "$dest"
    tar -xzf "$tmp/$name" -C "$dest" --strip-components=1
    echo "$name installed in $dest (SHA-256 checked)"
    "$dest/bin/java" -version
    exit 0
  fi
  if [ "$i" -lt "$attempts" ]; then sleep $((delay * i)); fi
done
echo "install-zulu-crac.sh: no $name matching its pinned SHA-256 after $attempts attempts ($url)" >&2
exit 1

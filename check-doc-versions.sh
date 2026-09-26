#!/usr/bin/env bash
# Verifies that the version numbers hardcoded across the documentation stay in
# sync with the reactor's actual version, instead of drifting silently the way
# docs/en/antora.yml and the former README_EN.md did (0.3.0-SNAPSHOT documented as the
# current snapshot while 0.4.0-SNAPSHOT had already shipped; 0.2.0 documented
# as the latest release while 0.3.0 had already been released to Central).
#
# Run from the repository root. Exits non-zero on the first mismatch found so
# it can gate CI (see .forgejo/workflows/pr.yml, unconditional step — it must
# run even on docs-only PRs, which is exactly where this class of bug creeps in).
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "check-doc-versions: $*" >&2; exit 1; }

# 1. Reactor version, straight from the root pom.xml.
POM_VERSION=$(python3 - <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse("pom.xml").getroot()
ns = root.tag[1:].split("}", 1)[0] if root.tag.startswith("{") else ""
v = root.find(f"{{{ns}}}version") if ns else root.find("version")
if v is None or not v.text:
    sys.exit("root <version> not found")
print(v.text.strip())
PY
)
[[ "$POM_VERSION" == *-SNAPSHOT ]] || fail "pom.xml version '$POM_VERSION' is not a -SNAPSHOT; is this script running against a release tag by mistake?"

# 2. docs/en/antora.yml attributes must track that same version.
ANTORA=docs/en/antora.yml
PROJECT_VERSION=$(sed -nE 's/^[[:space:]]*project-version:[[:space:]]*([^[:space:]]+)[[:space:]]*$/\1/p' "$ANTORA")
RELEASE_VERSION=$(sed -nE 's/^[[:space:]]*release-version:[[:space:]]*([^[:space:]]+)[[:space:]]*$/\1/p' "$ANTORA")
[[ -n "$PROJECT_VERSION" ]] || fail "'project-version' attribute not found in $ANTORA"
[[ -n "$RELEASE_VERSION" ]] || fail "'release-version' attribute not found in $ANTORA"
[[ "$PROJECT_VERSION" == "$POM_VERSION" ]] || fail "$ANTORA: project-version ($PROJECT_VERSION) != pom.xml version ($POM_VERSION) — update $ANTORA on every version bump"
[[ "$RELEASE_VERSION" != *-SNAPSHOT ]] || fail "$ANTORA: release-version ($RELEASE_VERSION) must be a released version, not a SNAPSHOT"

# release-version must be strictly older than the in-dev project-version
# (compares as tuples of ints, e.g. 0.3.0 < 0.4.0-SNAPSHOT -> (0,3,0) < (0,4,0)).
python3 - "$RELEASE_VERSION" "$PROJECT_VERSION" <<'PY'
import re, sys
def tup(v):
    v = re.sub(r"-SNAPSHOT$", "", v)
    return tuple(int(p) for p in v.split("."))
release, project = sys.argv[1], sys.argv[2]
if tup(release) >= tup(project):
    sys.exit(f"release-version ({release}) must be older than project-version ({project})")
PY

# 3. README.md pins the vidocq-runtime-maven-plugin example to the latest
#    *released* version — it must match release-version (it is plain Markdown,
#    so it cannot use the Antora {release-version} attribute directly).
README=README.md
README_PINNED=$(sed -nE 's#.*<version>([0-9]+\.[0-9]+\.[0-9]+)</version>.*#\1#p' "$README" | head -1)
[[ -n "$README_PINNED" ]] || fail "no pinned <version> found in $README's vidocq-runtime-maven-plugin snippet"
[[ "$README_PINNED" == "$RELEASE_VERSION" ]] || fail "$README pins vidocq-runtime-maven-plugin to $README_PINNED, but the latest released version is $RELEASE_VERSION"

# 4. Example poms must reference ${project.version} for their Docker image
#    tags, never a hardcoded literal that will inevitably fall out of date.
while IFS= read -r -d '' pom; do
  if grep -qE '<imageTag>vidocq/[a-zA-Z-]+:[0-9]+\.[0-9]+\.[0-9]+' "$pom"; then
    fail "$pom hardcodes a version in <imageTag> — use \${project.version} instead"
  fi
done < <(find vidocq-runtime-examples -maxdepth 2 -name pom.xml -print0)

echo "check-doc-versions: OK (project-version=$POM_VERSION, release-version=$RELEASE_VERSION)"

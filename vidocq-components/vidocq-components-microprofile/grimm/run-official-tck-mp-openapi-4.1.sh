#!/usr/bin/env zsh
set -euo pipefail

MODE="${1:-smoke}"

if [[ "$MODE" == "all" ]]; then
  echo "[grimm] Running full TCK suite"
  ./mvnw -ntp install -DskipTests
  mvn -f grimm-tck/pom.xml -Ptck-official test
elif [[ "$MODE" == "smoke" ]]; then
  echo "[grimm] Running smoke TCK suite"
  ./mvnw -ntp install -DskipTests
  mvn -f grimm-tck/pom.xml -Ptck-official -Dtest=GrimmTckSmokeTest test
else
  echo "[grimm] Running targeted TCK: $*"
  ./mvnw -ntp install -DskipTests
  mvn -f grimm-tck/pom.xml -Ptck-official test "$@"
fi


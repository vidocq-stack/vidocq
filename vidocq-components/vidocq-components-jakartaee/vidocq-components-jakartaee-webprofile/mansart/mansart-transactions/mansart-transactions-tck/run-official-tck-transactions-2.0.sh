#!/usr/bin/env bash
# Runner du TCK officiel Jakarta Transactions 2.0 contre l'implémentation Mansart.
#
# Modes :
#   smoke      (défaut) → 5 tests JUnit locaux qui valident le wiring de l'adaptateur
#                          (MansartTckProvider + MansartUserTransaction). N'a pas besoin du TCK.
#   tsharness            → suite TCK officielle via tsant — auto-récupération du zip Eclipse
#                          si absent, puis bin/tsant build && bin/tsant runclient sous .tck-cache/.
#   all                  → alias de tsharness.
#   -Dtest=Foo (libre)   → mvn test ciblé sur le module local (pour debug d'un smoke).
#
# Override :
#   TCK_VER=2.0.1   ou   TCK_URL=file:///… (cf. ../install-tck.sh)

set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-smoke}"
TCK_VER="${TCK_VER:-2.0.1}"
TCK_JAR="$HOME/.m2/repository/jakarta/transaction/jakarta.transaction-tck/${TCK_VER}/jakarta.transaction-tck-${TCK_VER}.jar"
TCK_DIR="../.tck-cache/transactions-tck"

LOG="target/tck-transactions-output.log"
REPORT="target/tck-report-transactions.txt"

# Activate Java 25 + Maven 4 toolchain pinned via ../.sdkmanrc
if [ -z "${SDKMAN_DIR:-}" ] && [ -d "$HOME/.sdkman" ]; then
    SDKMAN_DIR="$HOME/.sdkman"
fi
if [ -n "${SDKMAN_DIR:-}" ] && [ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
    set +u
    # shellcheck disable=SC1091
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
    (cd .. && sdk env > /dev/null 2>&1) || true
    set -u
fi

mkdir -p target

ensure_tck_extracted() {
    if [ -f "$TCK_JAR" ] && [ -d "$TCK_DIR/lib" ]; then return 0; fi
    echo ">>> TCK non installé → auto-récupération via ../install-tck.sh"
    if [ ! -x ../install-tck.sh ]; then
        echo "❌ ../install-tck.sh introuvable ou non exécutable."; return 78
    fi
    ../install-tck.sh install
}

run_smoke() {
    echo ">>> Build mansart-transactions (skipTests)"
    (cd .. && mvn -q -B -ntp install -DskipTests)
    echo ">>> Run smoke tests JUnit"
    mvn -B -ntp test -Dtest='MansartTckSmoke*' 2>&1 | tee "$LOG" || true
}

run_tsharness() {
    ensure_tck_extracted

    if ! command -v ant >/dev/null 2>&1; then
        cat <<EOF | tee -a "$LOG"

  Apache Ant n'est pas dans le PATH — requis par le harness tsharness.
  Installer via SDKMAN :  sdk install ant 1.10.14
                via brew :  brew install ant
EOF
        return 78
    fi

    echo ">>> Build mansart-transactions reactor (skipTests, install dans M2)"
    (cd .. && mvn -q -B -ntp install -DskipTests)

    echo ">>> Invoke profile full-tck (maven-antrun → tsharness build)"
    mvn -B -ntp -Pfull-tck antrun:run@tsharness-build 2>&1 | tee "$LOG"
}

case "$MODE" in
    smoke)
        run_smoke ;;
    tsharness|all)
        run_tsharness || true ;;
    -*)
        # Ciblé Surefire — laisse passer la flag à mvn (debug d'un smoke spécifique).
        echo ">>> Build mansart-transactions (skipTests)"
        (cd .. && mvn -q -B -ntp install -DskipTests)
        mvn -B -ntp test "$MODE" "$@" 2>&1 | tee "$LOG" || true
        ;;
    *)
        echo "Usage: $0 [smoke|tsharness|all|-Dtest=…]"; exit 64 ;;
esac

echo ""
echo ">>> Génération du rapport ($REPORT)"
{
    echo "Mansart Transactions 2.0 TCK Report"
    echo "Generated : $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo "Mode      : $MODE"
    echo "TCK ver   : $TCK_VER"
    echo ""
    grep -E "Tests run:|FAIL|PASS|ERROR|BUILD (SUCCESS|FAILURE)|^Pass:|^Fail:|^Error:" "$LOG" | tail -50 || echo "(no surefire/tsharness summary)"
} > "$REPORT"

echo ""
cat "$REPORT"

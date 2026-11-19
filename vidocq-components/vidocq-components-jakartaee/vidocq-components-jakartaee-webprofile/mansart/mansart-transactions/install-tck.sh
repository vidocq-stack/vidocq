#!/usr/bin/env bash
# install-tck.sh — télécharge et déploie le TCK officiel Jakarta Transactions 2.0
# depuis la distribution Eclipse Foundation.
#
# Le TCK Jakarta Transactions 2.0 est un « standalone TCK » historique (tsharness Sun-style),
# pas un jar Maven Surefire-scannable :
#   transactions-tck/
#     bin/                                ← scripts d'invocation (tsant, ts.jte, …)
#     lib/jtatck.jar                      ← LES tests TCK
#     lib/{sigtest,tsharness,javatest}.jar ← harness Sun
#     src/, doc/                          ← fixtures + procédure officielle
#
# On fait DEUX choses :
#   1. Installer `jtatck.jar` dans le M2 local sous l'artefact
#      `jakarta.transaction:jakarta.transaction-tck:<version>` pour que les builds Maven
#      puissent le déclarer en dependency (utile si on choisit plus tard d'écrire
#      un wrapper Surefire / Arquillian).
#   2. Conserver l'arborescence du TCK dépaquetée dans `.tck-cache/transactions-tck/`
#      à la racine de mansart-transactions/ — c'est cette arborescence qu'utilise
#      `bin/tsant` pour lancer la suite officielle.
#
# Usage :
#   ./install-tck.sh            # installe si absent
#   ./install-tck.sh --force    # ré-installe même si présent
#   ./install-tck.sh --verify   # vérifie seulement la présence
#
# Override (pour mirror local / version custom) :
#   TCK_URL=…    URL du zip officiel (défaut Eclipse Foundation)
#   TCK_VER=…    version, défaut 2.0.1

set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-install}"
TCK_VER="${TCK_VER:-2.0.1}"
TCK_URL="${TCK_URL:-https://download.eclipse.org/jakartaee/transactions/2.0/jakarta-transactions-tck-${TCK_VER}.zip}"

GROUP_PATH="$HOME/.m2/repository/jakarta/transaction"
TCK_JAR="$GROUP_PATH/jakarta.transaction-tck/${TCK_VER}/jakarta.transaction-tck-${TCK_VER}.jar"
TCK_CACHE_DIR="$(pwd)/.tck-cache"
TCK_EXTRACTED_DIR="$TCK_CACHE_DIR/transactions-tck"

verify() {
    local ok=0
    if [ -f "$TCK_JAR" ]; then
        echo "✅ jtatck.jar installé en M2 : $TCK_JAR"
    else
        echo "❌ jtatck.jar ABSENT du M2 (attendu : $TCK_JAR)"
        ok=1
    fi
    if [ -d "$TCK_EXTRACTED_DIR/lib" ]; then
        echo "✅ TCK arborescence dépaquetée : $TCK_EXTRACTED_DIR"
    else
        echo "❌ Arborescence TCK manquante (attendue : $TCK_EXTRACTED_DIR)"
        ok=1
    fi
    return $ok
}

case "$MODE" in
    --verify|-v) verify; exit $? ;;
    --force|-f)  FORCE=1 ;;
    install|"")  FORCE=0 ;;
    -h|--help)
        sed -n '2,20p' "$0"; exit 0 ;;
    *)
        echo "Usage: $0 [install|--force|--verify]"; exit 64 ;;
esac

if [ "$FORCE" = "0" ] && verify >/dev/null 2>&1; then
    verify
    echo "   (déjà installé, --force pour ré-installer)"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo ">>> Téléchargement TCK Jakarta Transactions ${TCK_VER}"
echo "    URL : $TCK_URL"
if ! curl -fsSL --retry 3 --retry-delay 2 -o "$TMP/tck.zip" "$TCK_URL"; then
    cat <<EOF

═══════════════════════════════════════════════════════════════════
  Téléchargement échoué.

  Le TCK Jakarta Transactions 2.0 peut nécessiter un mirror interne
  ou un download manuel. Solutions :

    1. Récupérer le zip manuellement depuis
         https://jakarta.ee/specifications/transactions/2.0/
       puis : TCK_URL=file:///chemin/vers/le.zip $0 --force

    2. Si le zip est déjà dépaquetté quelque part :
         cp -r /chemin/transactions-tck $TCK_EXTRACTED_DIR
         mvn install:install-file \\
              -Dfile=/chemin/transactions-tck/lib/jtatck.jar \\
              -DgroupId=jakarta.transaction \\
              -DartifactId=jakarta.transaction-tck \\
              -Dversion=${TCK_VER} -Dpackaging=jar
═══════════════════════════════════════════════════════════════════
EOF
    exit 1
fi

echo ">>> Décompression"
unzip -q -o "$TMP/tck.zip" -d "$TMP"

# Le zip Eclipse contient une racine `transactions-tck/` — on la déplace dans .tck-cache/.
SRC=$(find "$TMP" -maxdepth 2 -type d -name 'transactions-tck' | head -1)
if [ -z "$SRC" ]; then
    echo "❌ Dossier transactions-tck/ introuvable dans le zip."
    echo "   Contenu :"
    find "$TMP" -maxdepth 3 -type d | sed 's/^/     /'
    exit 1
fi

mkdir -p "$TCK_CACHE_DIR"
rm -rf "$TCK_EXTRACTED_DIR"
mv "$SRC" "$TCK_EXTRACTED_DIR"
echo ">>> Arborescence TCK dépaquetée : $TCK_EXTRACTED_DIR"

# Le jar principal des tests Jakarta Transactions est lib/jtatck.jar — on l'installe
# sous les coordonnées Maven canoniques pour qu'un wrapper futur (Arquillian/Surefire)
# puisse le déclarer en dependency proprement.
JTA_JAR="$TCK_EXTRACTED_DIR/lib/jtatck.jar"
if [ ! -f "$JTA_JAR" ]; then
    echo "❌ lib/jtatck.jar introuvable dans le TCK dépaqueté."
    ls -la "$TCK_EXTRACTED_DIR/lib/" 2>&1 | sed 's/^/     /' || true
    exit 1
fi

echo ">>> Installation M2 : jakarta.transaction:jakarta.transaction-tck:${TCK_VER}"
mvn -q -B -ntp install:install-file \
    -Dfile="$JTA_JAR" \
    -DgroupId=jakarta.transaction \
    -DartifactId=jakarta.transaction-tck \
    -Dversion="${TCK_VER}" \
    -Dpackaging=jar \
    -DcreateChecksum=true

# Auxiliaires utilisés par tsharness — installés sous io.vidocq.mansart:mansart-tck-harness:*
# pour les rendre accessibles depuis un wrapper Maven, sans polluer le namespace Jakarta.
for JAR in "$TCK_EXTRACTED_DIR/lib"/{tsharness,sigtest,javatest}.jar; do
    [ -f "$JAR" ] || continue
    NAME=$(basename "$JAR" .jar)
    echo "    + harness aux : $NAME"
    mvn -q -B -ntp install:install-file \
        -Dfile="$JAR" \
        -DgroupId=io.vidocq.mansart \
        -DartifactId="mansart-tck-${NAME}" \
        -Dversion="${TCK_VER}" \
        -Dpackaging=jar || true
done

echo ""
verify
echo ""
echo ">>> Suite complète : voir doc/ dans $TCK_EXTRACTED_DIR pour la procédure tsharness."
echo "    (un wrapper Maven sera ajouté en M6b si besoin)"

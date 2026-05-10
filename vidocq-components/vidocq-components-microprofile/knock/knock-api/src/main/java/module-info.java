/**
 * API Knock : re-exposition contrôlée de la spec MicroProfile Health 4.0 et SPI publique
 * stable. Le contenu sera étoffé au fil des milestones.
 *
 * <p><strong>Note JPMS — module automatique sans {@code Automatic-Module-Name}</strong>
 * (voir {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :</p>
 *
 * <p>{@code microprofile-health-api:4.0.1} n'a ni {@code Automatic-Module-Name} dans son
 * {@code MANIFEST.MF}, ni {@code module-info.class}. Le nom {@code microprofile.health.api}
 * est dérivé du nom d'artefact Maven par Java (strip version + remplacement {@code -} par
 * {@code .}). Ce nom est <em>stable</em> entre versions (la version est strippée avant
 * dérivation, l'artifact ID Eclipse MicroProfile n'a jamais changé).</p>
 *
 * <p>Pour la compilation, le POM parent force ce JAR sur le module-path via
 * {@code target/javamodules/} (voir {@code maven-dependency-plugin} en phase
 * {@code initialize}). Pour {@code jlink}, ce même répertoire passé au
 * {@code --module-path} suffit — jlink résout les modules automatiques par nom
 * de fichier exactement comme javac.</p>
 */
module io.vidocq.knock.api {
    requires transitive microprofile.health.api;

    exports io.vidocq.knock.spi;
}

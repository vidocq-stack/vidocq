/**
 * API Knock : re-exposition contrôlée de la spec MicroProfile Health 4.0 et SPI publique
 * stable. Le contenu sera étoffé au fil des milestones.
 *
 * <p><strong>Note JPMS — fork Vidocq du MicroProfile Health API</strong>
 * (voir {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :</p>
 *
 * <p>Knock dépend d'un fork `io.vidocq.knock:knock-mp-health-api` qui embarque
 * un {@code module-info.class}. Le nom de module reste {@code microprofile.health.api},
 * identique à l'upstream, ce qui assure la compatibilité des `requires`.</p>
 */
module io.vidocq.knock.api {
    requires transitive microprofile.health.api;

    exports io.vidocq.knock.spi;
}

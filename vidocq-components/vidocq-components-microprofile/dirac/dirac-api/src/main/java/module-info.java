/**
 * API Dirac : re-exposition contrôlée de la spec MicroProfile Metrics 5.1.1
 * et SPI publique stable de l'implémentation Vidocq.
 *
 * <p><strong>Note JPMS — module automatique éventuel sans {@code Automatic-Module-Name}</strong> :
 * Si {@code microprofile-metrics-api:5.1.1} n'a ni {@code Automatic-Module-Name} dans son
 * {@code MANIFEST.MF}, ni {@code module-info.class}, le nom JPMS utilisé est
 * {@code microprofile.metrics.api} (dérivé du nom d'artefact Maven par Java :
 * strip version + remplacement {@code -} par {@code .}).
 * Le POM parent force ce JAR sur le module-path via {@code target/javamodules/}
 * (voir {@code maven-dependency-plugin} en phase {@code initialize}).</p>
 *
 * <p>Contenu prévu (cf. ROADMAP.md M1+) :</p>
 * <ul>
 *   <li>Re-export transitif des annotations {@code @Counted}, {@code @Timed}, {@code @Gauge},
 *       {@code @Histogram} et du {@code MetricRegistry}.</li>
 *   <li>Types publics stables : {@code HistogramSnapshot}, {@code TimerSnapshot}.</li>
 *   <li>{@code DiracContext} — contexte d'initialisation exposé aux composants du core.</li>
 * </ul>
 */
module io.vidocq.dirac.api {
    requires transitive microprofile.metrics.api;

    exports io.vidocq.dirac.api;
}

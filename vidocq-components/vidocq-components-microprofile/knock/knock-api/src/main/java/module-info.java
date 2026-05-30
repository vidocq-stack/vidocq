/**
 * Knock API: controlled re-exposure of the MicroProfile Health 4.0 spec and a stable
 * public SPI. The content will be expanded as milestones progress.
 *
 * <p><strong>JPMS note — Vidocq fork of the MicroProfile Health API</strong>
 * (see {@code docs/adr/ADR-001-jpms-workaround-microprofile-health.md}) :</p>
 *
 * <p>Knock depends on a fork `io.vidocq.knock:knock-mp-health-api` that includes
 * a {@code module-info.class}. The module name remains {@code microprofile.health.api},
 * identical to upstream, which preserves {@code requires} compatibility.</p>
 */
module io.vidocq.knock.api {
    requires transitive microprofile.health.api;

    exports io.vidocq.knock.spi;
}

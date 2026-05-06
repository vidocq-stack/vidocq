/**
 * Vidocq extension that fails fast on boot if Mansart Data cannot reach its {@link
 * javax.sql.DataSource} and logs the {@code @Repository} interfaces wired by the underlying
 * {@code mansart-data-cdi} BCE.
 *
 * <p>The actual repository discovery is done by Vauban's automatic scan of
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * — this extension does not register the BCE itself; it only validates wiring at boot.
 */
module io.vidocq.mpserver.ext.mansart.data {
    requires transitive io.vidocq.mpserver.spi;
    requires io.vidocq.vauban.core;
    requires transitive io.vidocq.mansart.data.core;
    requires transitive io.vidocq.mansart.data.cdi;
    requires transitive jakarta.data;
    requires jakarta.cdi;
    requires java.sql;        // javax.sql.DataSource

    provides io.vidocq.mpserver.spi.VidocqExtension
            with io.vidocq.mpserver.ext.mansart.data.MansartDataIntegrationExtension;
}

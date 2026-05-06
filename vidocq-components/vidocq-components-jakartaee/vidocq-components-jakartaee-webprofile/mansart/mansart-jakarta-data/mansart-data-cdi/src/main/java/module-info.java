/**
 * CDI 4.1 bootstrap for Mansart Data — BuildCompatibleExtension reading {@code META-INF/mansart-repositories.list}.
 * Implementation in M3.
 */
module io.vidocq.mansart.data.cdi {
    requires transitive io.vidocq.mansart.data.core;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.data;        // M7-4 — @Enhancement uses Repository.class in the typed BCE API
    requires java.sql;            // javax.sql.DataSource, javax.sql.XADataSource
    requires java.naming;         // M9 — JNDI lookup for dataStore values starting with "java:"

    exports io.vidocq.mansart.data.cdi;

    // Vauban (or any compliant CDI Lite container) instantiates beans of this jar via
    // MethodHandles.privateLookupIn. In module-path mode this requires opens-to. We open
    // narrowly to vauban-core only — the package stays sealed for everyone else.
    opens io.vidocq.mansart.data.cdi to io.vidocq.vauban.core;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.mansart.data.cdi.MansartDataExtension;
}

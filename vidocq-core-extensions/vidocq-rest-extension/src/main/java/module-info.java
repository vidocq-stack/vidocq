module fr.vidocq.vidocq.ext.rest {
    requires fr.vidocq.vidocq.spi;
    requires fr.vidocq.vauban.core;

    requires jakarta.cdi;
    requires jakarta.ws.rs;
    requires org.glassfish.jersey.core.server;
    requires org.glassfish.jersey.container.grizzly2.http;
    requires org.glassfish.jersey.inject.hk2;
    requires org.glassfish.hk2.utilities;
    requires org.glassfish.hk2.api;
    requires org.glassfish.grizzly.http.server;

    opens fr.vidocq.vidocq.ext.rest to fr.vidocq.vauban.core;

    provides fr.vidocq.vidocq.spi.VidocqExtension
            with fr.vidocq.vidocq.ext.rest.RestExtension;

    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with fr.vidocq.vidocq.ext.rest.RestScopeExtension;
}

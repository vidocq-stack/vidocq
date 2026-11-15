package acme.app;

import acme.pages.Pages;
import io.vidocq.runtime.spi.ApplicationLayer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;

import java.util.ServiceLoader;
import java.util.spi.ToolProvider;

/** A CDI observer, so that the dev reload, which boots without the application's main, runs the lookup too. */
@ApplicationScoped
public class Probe {

    void onStart(@Observes Startup startup) {
        var found = Pages.find("index.html");
        System.out.println(found != null ? "LOOKUP-FOUND " + found : "LOOKUP-MISSING");
        System.out.println("LOOKUP-LOADER " + Pages.class.getClassLoader().getName());
        System.out.println(acme.sql.Sql.probe());
        // The copies of the application module the launch loaded: one, or two when the module sits in the boot layer
        // and in the application layer (BUG-20261114-10). Each one is created, through AppTool.provider().
        long tools = ServiceLoader.load(ToolProvider.class).stream()
                .filter(provider -> provider.type().getName().equals(AppTool.TYPE))
                .map(ServiceLoader.Provider::get)
                .filter(tool -> tool.name().equals("acme-app-tool")).count();
        System.out.println("TOOL-PROVIDERS " + tools);
        // What a library that lists a directory of the application finds, such as a migration engine: no class loader
        // lists one in an application layer, nor in a jlink image, which has no directory entry (BUG-20261115-02).
        System.out.println("LIST-HOME " + ApplicationLayer.home()
                .map(layer -> ApplicationLayer.list(layer, "META-INF/acme")).orElse(null));
        Pages.startPoller();
    }
}

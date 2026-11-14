package acme.app;

import acme.pages.Pages;
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
        // and in the application layer (BUG-20261114-10).
        long tools = ServiceLoader.load(ToolProvider.class).stream()
                .filter(provider -> provider.type().getName().equals(AppTool.TYPE)).count();
        System.out.println("TOOL-PROVIDERS " + tools);
        Pages.startPoller();
    }
}

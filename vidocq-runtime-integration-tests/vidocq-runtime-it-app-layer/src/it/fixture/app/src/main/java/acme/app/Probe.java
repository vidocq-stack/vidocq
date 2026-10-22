package acme.app;

import acme.pages.Pages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;

/** A CDI observer, so that the dev reload, which boots without the application's main, runs the lookup too. */
@ApplicationScoped
public class Probe {

    void onStart(@Observes Startup startup) {
        var found = Pages.find("index.html");
        System.out.println(found != null ? "LOOKUP-FOUND " + found : "LOOKUP-MISSING");
        System.out.println("LOOKUP-LOADER " + Pages.class.getClassLoader().getName());
        System.out.println(acme.sql.Sql.probe());
        Pages.startPoller();
    }
}

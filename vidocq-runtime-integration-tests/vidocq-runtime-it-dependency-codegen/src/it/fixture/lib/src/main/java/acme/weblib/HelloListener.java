package acme.weblib;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/** A listener only the container's annotation scan finds: nothing in the application names it. */
@WebListener
public class HelloListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        System.out.println("LISTENER-RAN");
    }
}

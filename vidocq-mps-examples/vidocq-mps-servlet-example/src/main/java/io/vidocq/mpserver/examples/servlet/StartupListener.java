package io.vidocq.mpserver.examples.servlet;

import io.vidocq.mpserver.ext.servlet.chappe.container.VidocqServletContext;
import io.vidocq.mpserver.ext.servlet.chappe.security.AuthenticatedUser;
import io.vidocq.mpserver.ext.servlet.chappe.security.SecurityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Installe un {@link SecurityProvider} de démonstration au démarrage et journalise
 * les événements de cycle de vie.
 */
@ApplicationScoped
@WebListener
public class StartupListener implements ServletContextListener {

    private static final System.Logger LOG = System.getLogger(StartupListener.class.getName());

    private static final Map<String, String> PASSWORDS = Map.of(
            "alice", "admin",
            "bob", "user"
    );
    private static final Map<String, Set<String>> ROLES = Map.of(
            "alice", Set.of("admin"),
            "bob", Set.of("user")
    );

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (sce.getServletContext() instanceof VidocqServletContext ctx) {
            ctx.setSecurityProvider(demoProvider());
            LOG.log(System.Logger.Level.INFO,
                    "Security provider installed (users: alice/admin, bob/user)");
        }
        LOG.log(System.Logger.Level.INFO, "Servlet example ready");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LOG.log(System.Logger.Level.INFO, "Servlet example stopping");
    }

    private static SecurityProvider demoProvider() {
        return (user, pass) -> {
            if (!PASSWORDS.getOrDefault(user, "").equals(pass)) return Optional.empty();
            return Optional.of(new AuthenticatedUser(user, ROLES.getOrDefault(user, Set.of())));
        };
    }
}

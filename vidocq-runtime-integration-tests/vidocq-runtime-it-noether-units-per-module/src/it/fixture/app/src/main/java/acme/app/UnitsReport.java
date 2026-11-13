package acme.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.metamodel.EntityType;

/**
 * Prints, once the container started, the entities each unit manages and what one transaction wrote through both:
 * the line UnitsPerModuleIT reads.
 */
@ApplicationScoped
public class UnitsReport {

    @PersistenceUnit(unitName = "ledger")
    EntityManagerFactory ledger;

    @PersistenceUnit(unitName = "archive")
    EntityManagerFactory archive;

    @Inject
    Recorder recorder;

    void report(@Observes Startup startup) {
        recorder.record("first");
        System.out.println("UNITS ledger=" + entities(ledger) + " archive=" + entities(archive)
                + " entries=" + recorder.entries() + " notes=" + recorder.notes());
    }

    private static String entities(EntityManagerFactory factory) {
        return factory.getMetamodel().getEntities().stream()
                .map(type -> type.getJavaType().getName())
                .sorted()
                .toList()
                .toString();
    }
}

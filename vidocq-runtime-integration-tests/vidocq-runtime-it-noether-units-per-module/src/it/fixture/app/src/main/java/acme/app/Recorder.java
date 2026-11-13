package acme.app;

import acme.ledger.Entry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

/**
 * Writes through both units in one transaction: the library's by its name, the application's by its qualifier. The
 * qualified entity manager is {@code @TransactionScoped}, the default scope of a unit's entity manager bean: it is
 * reached within a transaction only, so the counts run in one too.
 */
@ApplicationScoped
public class Recorder {

    @PersistenceContext(unitName = "ledger")
    EntityManager entries;

    @Inject
    @Archived
    EntityManager notes;

    @Transactional
    public void record(String id) {
        entries.persist(new Entry(id));
        notes.persist(new Note(id));
    }

    @Transactional
    public long entries() {
        return entries.createQuery("select count(e) from Entry e", Long.class).getSingleResult();
    }

    @Transactional
    public long notes() {
        return notes.createQuery("select count(n) from Note n", Long.class).getSingleResult();
    }
}

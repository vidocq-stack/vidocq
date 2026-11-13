package acme.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** The library's entity: only the library's unit, ledger, manages it. */
@Entity
public class Entry {

    @Id
    private String id;

    protected Entry() {
    }

    public Entry(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}

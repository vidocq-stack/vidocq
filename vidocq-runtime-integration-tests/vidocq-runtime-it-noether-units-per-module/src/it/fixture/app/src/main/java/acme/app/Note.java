package acme.app;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** The application's entity: only the application's unit, archive, manages it. */
@Entity
public class Note {

    @Id
    private String id;

    protected Note() {
    }

    public Note(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}

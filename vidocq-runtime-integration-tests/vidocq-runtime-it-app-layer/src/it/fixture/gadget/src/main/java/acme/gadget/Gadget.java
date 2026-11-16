package acme.gadget;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A class of a library that knows nothing of CDI. Its package-private member makes it a class only a class of
 * {@code acme.gadget} can extend in full: a subclass of another package cannot override {@link #label(String)}.
 */
public class Gadget {

    private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

    private final String name;

    public Gadget() {
        this("unnamed");
    }

    public Gadget(String name) {
        this.name = name;
        CONSTRUCTED.incrementAndGet();
    }

    public String name() {
        return name;
    }

    /** Package-private: what {@link Gadgets#labelOf(Gadget, String)} calls. */
    String label(String suffix) {
        return name + ":" + suffix;
    }

    /** How many times a constructor of this class ran: once per gadget, and once more when a client proxy runs it. */
    public static int constructed() {
        return CONSTRUCTED.get();
    }
}

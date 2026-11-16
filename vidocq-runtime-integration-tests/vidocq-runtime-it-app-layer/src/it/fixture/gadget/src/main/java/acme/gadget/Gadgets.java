package acme.gadget;

/** Code of the library that uses a gadget through its package-private member, as a library does with its own types. */
public final class Gadgets {

    private Gadgets() {
    }

    /**
     * The label of {@code gadget}. On a client proxy, the answer is the produced instance's only when the proxy
     * overrides the package-private member: a proxy that does not answers from its own, unnamed, state.
     */
    public static String labelOf(Gadget gadget, String suffix) {
        return gadget.label(suffix);
    }
}

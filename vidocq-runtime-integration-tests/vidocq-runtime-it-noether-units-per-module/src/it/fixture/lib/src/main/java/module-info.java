module acme.ledger {
    requires transitive jakarta.persistence;
    requires io.vidocq.noether.jpa.core;

    exports acme.ledger;

    provides io.vidocq.noether.jpa.core.spi.ManagedAccessProvider with acme.ledger._NoetherJpaAccess;
}

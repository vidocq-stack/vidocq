module acme.app {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.transaction;

    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.transactions;
    requires io.vidocq.noether.jpa.core;
    // The library module, whose persistence.xml declares the unit ledger.
    requires acme.ledger;

    exports acme.app;
}

module acme.jsp {
    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.web.ibarra;
    // vidocq:generate compiles the pages into this module, which reads what they use.
    requires io.vidocq.ibarra.api;
    requires io.vidocq.peano.api;
}

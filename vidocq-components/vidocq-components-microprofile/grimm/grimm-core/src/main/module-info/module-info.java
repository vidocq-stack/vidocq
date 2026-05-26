module io.vidocq.grimm.core {
  requires microprofile.openapi.api;
  requires jakarta.ws.rs;
  requires jakarta.annotation;
  requires org.eclipse.microprofile.config;

  exports io.vidocq.grimm.internal to io.vidocq.grimm.cdi.vauban;
}


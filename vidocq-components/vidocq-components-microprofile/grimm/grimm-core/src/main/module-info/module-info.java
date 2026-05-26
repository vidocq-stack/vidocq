module io.vidocq.grimm.core {
  requires org.eclipse.microprofile.openapi;
  requires jakarta.ws.rs;
  requires jakarta.annotation;
  requires org.eclipse.microprofile.config;

  exports io.vidocq.grimm.internal to io.vidocq.grimm.cdi.vauban;
  exports io.vidocq.grimm.internal.config to io.vidocq.grimm.cdi.vauban;
  exports io.vidocq.grimm.internal.merger to io.vidocq.grimm.cdi.vauban;
  exports io.vidocq.grimm.internal.schema to io.vidocq.grimm.cdi.vauban;

  provides org.eclipse.microprofile.openapi.spi.OASFactoryResolver
      with io.vidocq.grimm.internal.factory.GrimmOASFactoryResolver;
}


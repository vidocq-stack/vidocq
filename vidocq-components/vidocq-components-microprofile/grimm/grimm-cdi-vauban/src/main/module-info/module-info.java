module io.vidocq.grimm.cdi.vauban {
  requires io.vidocq.grimm.core;
  requires org.eclipse.microprofile.openapi;
  requires jakarta.cdi;
  requires jakarta.inject;
  requires jakarta.ws.rs;
  requires jakarta.annotation;
  requires org.eclipse.microprofile.config;

  exports io.vidocq.grimm.cdi;

  provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
      with io.vidocq.grimm.cdi.GrimmExtension;
}


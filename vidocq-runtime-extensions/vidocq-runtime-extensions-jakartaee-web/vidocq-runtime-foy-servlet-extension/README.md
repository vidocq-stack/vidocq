# vidocq-runtime-foy-servlet-extension

Vidocq Runtime extension for Jakarta Servlet 6.1: it deploys the application's servlets, filters,
listeners, `web.xml`, web fragments and `ServletContainerInitializer`s with
[Foy](https://codefloe.com/Vidocq/foy) and mounts them on the shared Chappe web server, next to
the other extensions (REST, OpenAPI, static files). Foy activates the CDI request context for
every request and, through `foy-cdi-vauban` and `vauban-webcontexts`, the session context.

## Use

```xml
<dependency>
    <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
    <artifactId>vidocq-runtime-foy-servlet-extension</artifactId>
</dependency>
```

and, in `<annotationProcessorPaths>`, the companion bundle
`vidocq-runtime-foy-servlet-extension-codegen`, without `<type>`: it is a jar since 0.4.0. `vidocq extension add foy-servlet`
adds both.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `vidocq.servlet.context-path` | the `web.xml` `<default-context-path>`, else `/` | Context path and mount prefix |
| `vidocq.servlet.listener` | `default` | Chappe listener of the mount |
| `vidocq.servlet.session-timeout` | `1800` | Session timeout in seconds, when no descriptor sets one |
| `vidocq.servlet.users.<name>.password`, `.roles` | none | Development users of Foy's in-memory identity store |
| `vidocq.servlet.confidential-port` | none (403) | HTTPS port of `CONFIDENTIAL`/`INTEGRAL` constraints |
| `vidocq.servlet.rotate-session-id-on-login` | `true` | Change the session id on login |

Security constraints authenticate against the application's `IdentityStore` bean
(`io.vidocq.foy.spi.security`) when it has one, else against the configured users; both together
fail at startup.

`vidocq.http.mount.<name>.type=servlet` mounts the application declaratively instead.

Documentation: the *Servlet (Foy)* section of
[`docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc`](../../../docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc).

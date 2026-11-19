# Servlet extension (Foy on Chappe) — design

Date: 2026-10-11. Status: approved in conversation, pending spec review.

## Goal

Bring Jakarta Servlet 6.1 to the Vidocq runtime by assembling the Foy brick with Vauban,
`vauban-webcontexts` (session scope) and the shared Chappe web server. Today the runtime
references Foy nowhere: the extensions page says "Planned", the README says "Brick only".

Success: an application that depends on the extension gets its `@WebServlet`, `@WebFilter`,
`@WebListener`, `web.xml` components and `ServletContainerInitializer`s served on the
runtime's HTTP port, with working `@RequestScoped` and `@SessionScoped` beans, next to its
REST resources, on the module path.

## Decisions

- **REST and Servlet side by side.** Cassini and Foy are two separate `ChappeMountPoint`
  mounts on the same server, each on its own prefix. Servlet filters and the `HttpSession`
  do not apply to REST resources. Running Cassini inside Foy is an opt-in property tracked
  by [vidocq#215](https://codefloe.com/Vidocq/vidocq/issues/215).
- **First PR scope:** runtime module, codegen module, one IT, docs. Out of scope and
  tracked separately: the dev console panel
  ([vidocq#216](https://codefloe.com/Vidocq/vidocq/issues/216)) and the Servlet TCK on the
  assembled runtime ([vidocq#217](https://codefloe.com/Vidocq/vidocq/issues/217)).
- **No change to Foy.** Everything needed already exists on Foy's `main`:
  `FoyChappeBoot` returns a mountable handler without owning a server, `foy-cdi-vauban`
  provides the session context through the `CdiContextListeners` SPI, and Foy activates
  the request context itself.

## Modules

Under `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/` (Servlet is a
Web Profile spec):

### `vidocq-runtime-foy-servlet-extension`

Module `io.vidocq.runtime.extensions.jakartaee.web.foy`.

- `FoyExtension` — `VidocqExtension`, priority 600 (after Cassini, 500; before
  `ChappeMountConfigExtension`, 7000, and `ChappeServerBootstrap`, 10000).
- `FoyMountHandlerProvider` — `MountHandlerProvider` of type `servlet`, so that
  `vidocq.http.mount.<n>.type=servlet` mounts Foy declaratively, like `restful`.
- `ServletStartupSection` — startup report section: listener, prefix, number of servlets
  and filters, session timeout.
- Registered in `module-info.java` (`provides`) and `META-INF/services`.
- Dependencies: `foy-chappe`, `foy-cdi-vauban` (brings `vauban-webcontexts`),
  `vidocq-runtime-chappe-webserver-extension`, `vidocq-runtime-spi`.

### `vidocq-runtime-foy-servlet-extension-codegen`

POM-only bundle for `<annotationProcessorPaths>`, like
`vidocq-runtime-cassini-rest-extension-codegen`: `foy-processor` (the `$$FoyComponent`
factories) and `foy-cdi-vauban`, whose build-compatible extensions (`FoyWebExtension`, and
`WebContextsExtension` through `vauban-webcontexts`) must be on the processor path for
Vauban's processor to run them.

### Root POM

Adds `foy.version` and `dependencyManagement` entries for `foy-api`, `foy-core`,
`foy-chappe`, `foy-cdi-vauban`, `foy-processor` and `vauban-webcontexts`. Foy depends only
on Jakarta specs and other Vidocq bricks, so the zero-dependency rule holds; the change is
reviewed with the `dependency-gatekeeper` agent.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `vidocq.servlet.context-path` | the `web.xml` `<default-context-path>`, else `/` | Prefix of the Foy mount |
| `vidocq.servlet.listener` | default listener | Chappe listener the mount goes to |
| `vidocq.servlet.session-timeout` | the `web.xml` value, else 30 min | Session timeout, in seconds |

The keys are declared through `configKeys()`.

## Runtime behaviour

`FoyExtension.onStart`:

1. If a declarative `vidocq.http.mount.*.type=servlet` mount exists, do nothing (same rule
   as Cassini's `restful`).
2. Build `FoyChappeBoot` with Vauban's `BeanManager`, the application class loader (the
   thread context class loader of the application layer, used for `web.xml`, fragments and
   `ServletContainerInitializer` discovery), the context path and the session timeout.
3. Empty result (no servlet, filter, listener nor initializer): log at INFO
   "Foy Servlet extension inactive" and return.
4. Otherwise mount the handler on the listener with `ChappeMountPoint.instance().mount`.

The extension does not wrap the dispatch in a request scope: Foy activates the request
context itself (`RequestContextController`) and the session context through
`foy-cdi-vauban`.

`FoyExtension.onStop` closes the `Mounted`: sessions are destroyed, their `@PreDestroy`
methods run and `@Destroyed(SessionScoped.class)` is fired. An in-JVM hot reload then
starts from a fresh deployment on the new class loader.

**Prefix routing and conflicts (shared web server).** Chappe's router tries mounts in
registration order and takes the first whose prefix matches, so a Foy mount on `/` would
shadow every mount registered after it (OpenAPI, static files, declarative mounts).
`ChappeMountPoint` therefore keeps the mounts of each listener and hands them to the router
**longest prefix first** when the server starts; two mounts on the same prefix of the same
listener fail at the second `mount` call, with a message naming both owners and the keys
that move them (`vidocq.rest.context-path`, `vidocq.servlet.context-path`,
`vidocq.http.mount.<n>.path`). This is a change of the shared web server, made in this PR
and listed in What's new: before it, a duplicate prefix silently left one mount unreachable.

## Tests

TDD: every test is written before its code.

Unit tests (extension module):

- no web component: nothing mounted, startup continues;
- declarative `type=servlet` mount present: auto-mount skipped;
- `FoyMountHandlerProvider` creates a handler on the configured prefix;
- `ChappeMountPoint`: a longer prefix wins over `/` whatever the registration order, and a
  second mount on the same prefix of the same listener fails with the message above.

IT `vidocq-runtime-it-foy-servlet` in `vidocq-runtime-integration-tests`, modelled on
`vidocq-runtime-it-cyrano-jpms`: the application is a named module (`src/main` with a
`module-info`), the test boots the assembled runtime with `VidocqBootstrap` on the module
path and talks HTTP to the bound port. Test
application: a `@WebServlet`, a filter, a listener, a `@SessionScoped` cart, a
`@RequestScoped` bean and a REST resource under `/api`. Scenarios:

1. the servlet answers and the filter runs;
2. the cart is kept across two requests carrying the session cookie, and two clients get
   two carts;
3. `invalidate()` destroys the session bean and fires `@Destroyed(SessionScoped.class)`;
4. eight concurrent requests each get their own `@RequestScoped` instance;
5. `/api/...` reaches Cassini and `/...` reaches Foy, on the same port.

The session scenarios reproduce the logic of Foy's `foy-it-session-scenarios`, copied
rather than depended upon (it is a Foy test module).

## Documentation (same PR)

- Antora `modules/vidocq-runtime-extensions.adoc`: replace "Planned" with a Servlet section
  marked NEW (dependencies, codegen, configuration keys, coexistence with REST, link to
  vidocq#215).
- `README.md`: replace "Brick only: no extension in this repository".
- `WEB-PROFILE.md`: the Servlet row states that the extension is delivered.
- What's new entry.

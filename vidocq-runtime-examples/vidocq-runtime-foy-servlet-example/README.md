# Vidocq Runtime — Servlet Example (Vidocq Bistro)

A small Jakarta Servlet 6.1 application on the Vidocq stack: **Foy** serves the servlets on
the **Chappe** server, and **Vauban** makes every web component a CDI bean, with the request and
session scopes of `vauban-webcontexts`.

| Class | What it shows |
|---|---|
| `MenuServlet` (`""`, the root) | The menu, with a form POST per dish |
| `BasketServlet` (`/basket`) | The `@SessionScoped` `Basket`, POST then redirect then GET, the order sent to the kitchen |
| `KitchenServlet` (`/kitchen`) | `@ServletSecurity(@HttpConstraint(rolesAllowed = "chef"))`, BASIC authentication |
| `TicketFilter` (`/*`) | A `@WebFilter` that injects the `@RequestScoped` `RequestTicket` the servlet sees too |
| `SessionCounter` | A `@WebListener` (`HttpSessionListener`) with an injected `@ApplicationScoped` bean |
| `Menu`, `Kitchen` | `@ApplicationScoped` services |

Every page is written by a servlet: no JSP, no static file. The `module-info.java` has no
exports, no opens and no hand-written `provides`.

## Run

Build the runtime first, from the root of the `vidocq` repository:

```bash
./mvnw -ntp -DskipTests install
```

then start the example in dev mode:

```bash
cd vidocq-runtime-examples/vidocq-runtime-foy-servlet-example
../../mvnw vidocq:dev
```

- Menu and basket: <http://localhost:8080/>
- Kitchen: <http://localhost:8080/kitchen>, user `chef`, password `chef` (from `vidocq.properties`)
- Dev console: the URL the log prints (`Vidocq dev console: http://127.0.0.1:8888/`), with the
  active sessions of the Servlet panel

Every response carries `X-Request-Id`; the page footer shows the same id, read from the same
`@RequestScoped` bean by the filter and the servlet. `src/test/http/bistro.http` walks the
whole flow from IntelliJ.

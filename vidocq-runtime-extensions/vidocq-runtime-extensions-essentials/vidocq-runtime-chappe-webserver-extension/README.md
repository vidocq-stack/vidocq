# vidocq-runtime-chappe-webserver-extension

HTTP engine foundation for Vidocq. Exposes a single attachment point — `ChappeMountPoint` — that other extensions (`vidocq-servlet-chappe-extension`, `vidocq-rest-chappe-extension`, ...) use to contribute their Chappe `Handler` instances.

One Chappe server (`fr.vidocq.chappe.api.Server`) is started per declared listener.

## Lifecycle

| Phase | Extension | Priority | Action |
|---|---|---|---|
| configure | `ChappeEngineExtension` | 100 | installs `ChappeMountPoint` |
| onStart | contributors | 500–9 999 | call `mount(...)` |
| onStart | `ChappeServerBootstrap` | 10 000 | starts one `Server` per listener |
| onStop | `ChappeServerBootstrap` | 10 000 | stops the servers |

## Usage from a contributor extension

```java
@Override
public void onStart(ExtensionContext ctx) {
    ChappeMountPoint mp = ChappeMountPoint.instance();
    mp.mount("/api", myHandler);
    // or: mp.router(ChappeListener.DEFAULT).get("/hello", h -> Response.ok("hi"));
}
```

## Configuration

| Key | Default | Description |
|---|---|---|
| `vidocq.chappe.listeners` | `default` | CSV list of listeners |
| `vidocq.chappe.listener.<name>.host` | `0.0.0.0` | listening host |
| `vidocq.chappe.listener.<name>.port` | `8080` (for `default`) | listening port |
| `vidocq.http.host` | `0.0.0.0` | alias for `vidocq.chappe.listener.default.host` |
| `vidocq.http.port` | `8080` | alias for `vidocq.chappe.listener.default.port` |

The `vidocq.http.*` aliases address the listener named `default` only — the more discoverable
names, used by the CLI (`vidocq start --port`), by every scaffolded `vidocq.properties` and by the
reference documentation. The explicit `vidocq.chappe.listener.default.*` key always wins, and a
listener other than `default` must declare its own port.

Multi-listener:

```properties
vidocq.chappe.listeners=default,admin
vidocq.chappe.listener.default.port=8080
vidocq.chappe.listener.admin.host=127.0.0.1
vidocq.chappe.listener.admin.port=9090
```

## Limitations (current milestone)

- TLS not implemented (planned: `.tls=true` attribute + keystore).
- `ChappeMountPoint` accessible via a static holder — a CDI `@Produces` exposure will be added later without breaking the API.

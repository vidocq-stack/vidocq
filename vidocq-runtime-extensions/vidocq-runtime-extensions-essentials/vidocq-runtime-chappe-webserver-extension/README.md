# vidocq-runtime-chappe-webserver-extension

HTTP engine foundation for Vidocq. Exposes a single attachment point — `ChappeMountPoint` — that other extensions (`vidocq-servlet-chappe-extension`, `vidocq-rest-chappe-extension`, ...) use to contribute their Chappe `Handler` instances.

One Chappe server (`fr.vidocq.chappe.api.Server`) is started per declared listener.

## Lifecycle

| Phase | Extension | Priority | Action |
|---|---|---|---|
| configure | `ChappeEngineExtension` | 100 | installs `ChappeMountPoint` |
| onStart | contributors | 500–9 999 | call `mount(...)`, may `declareListener(...)` |
| onStart | `ChappeServerBootstrap` | 10 000 | starts one `Server` per listener, logs the address it bound |
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

## A listener of an extension's own

An extension that serves something apart from the application, such as the dev console, declares its
listener from its `onStart` (priority between 100 and 10 000), then mounts on it by name:

```java
@Override
public void onStart(ExtensionContext ctx) {
    ChappeMountPoint mp = ChappeMountPoint.instance();
    mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 8888),
            new ListenerOptions(true, true, Duration.ofSeconds(1), this::bound));
    mp.router("dev").get("/api/snapshot", snapshot);
}
```

`ListenerOptions` (or `ListenerOptions.DEFAULTS`, which starts it like a configured listener):

| Option | Effect |
|---|---|
| `anyPortWhenTaken` | the port is in use: listen on a free port instead of failing the boot, with a WARNING naming both ports |
| `quiet` | the `Chappe listener '…' started on …` line is logged at DEBUG, for an extension that prints its own |
| `shutdownGracePeriod` | how long stopping the server waits for the requests in flight; `null` keeps Chappe's 30 s |
| `onBound` | called on the boot thread once the server listens, with the address it bound; it cannot mount any more, and an exception it throws is logged, never fatal |

A name has one owner. The extension's listeners start first, then the configuration's, and
`vidocq.chappe.listeners` must not list a name an extension declared: the boot fails with
`listener 'dev' is declared by an extension (DevConsoleExtension); remove it from vidocq.chappe.listeners`.
The listener named `default` is always the application's.

Every listener logs the address it bound, not the one it was given: `port=0` prints the real port. A
wildcard host reads `localhost`, an IPv6 address is bracketed (`http://[::1]:8888/`), and
`ChappeListener.httpUrl(InetSocketAddress)` builds the same URL from the address `onBound` receives.

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

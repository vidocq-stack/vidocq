# vidocq-chappe-extension

Socle moteur HTTP pour Vidocq. Expose un point d'accrochage unique — `ChappeMountPoint` — que les autres extensions (`vidocq-servlet-chappe-extension`, `vidocq-rest-chappe-extension`, ...) utilisent pour contribuer leurs `Handler` Chappe.

Un serveur Chappe (`fr.vidocq.chappe.api.Server`) est démarré par listener déclaré.

## Cycle de vie

| Phase | Extension | Priorité | Action |
|---|---|---|---|
| configure | `ChappeEngineExtension` | 100 | installe `ChappeMountPoint` |
| onStart | contributeurs | 500–9 999 | appellent `mount(...)` |
| onStart | `ChappeServerBootstrap` | 10 000 | démarre un `Server` par listener |
| onStop | `ChappeServerBootstrap` | 10 000 | arrête les serveurs |

## Usage côté extension contributrice

```java
@Override
public void onStart(ExtensionContext ctx) {
    ChappeMountPoint mp = ChappeMountPoint.instance();
    mp.mount("/api", myHandler);
    // ou : mp.router(ChappeListener.DEFAULT).get("/hello", h -> Response.ok("hi"));
}
```

## Configuration

| Clé | Défaut | Description |
|---|---|---|
| `vidocq.chappe.listeners` | `default` | liste CSV des listeners |
| `vidocq.chappe.listener.<name>.host` | `0.0.0.0` | hôte d'écoute |
| `vidocq.chappe.listener.<name>.port` | `8080` (pour `default`) | port d'écoute |

Multi-listener :

```properties
vidocq.chappe.listeners=default,admin
vidocq.chappe.listener.default.port=8080
vidocq.chappe.listener.admin.host=127.0.0.1
vidocq.chappe.listener.admin.port=9090
```

## Limitations (jalon actuel)

- TLS non implémenté (prévu : attribut `.tls=true` + keystore).
- `ChappeMountPoint` accessible via holder statique — une exposition CDI `@Produces` sera ajoutée plus tard sans casser l'API.

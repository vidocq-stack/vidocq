# vidocq-servlet-example

Exemple applicatif Jakarta Servlet 6.1 sur Chappe, intégré à Vauban CDI.

Tout le cycle Vidocq est exercé : découverte CDI, moteur Chappe partagé, dispatch servlet, filter chain, sessions, security BASIC.

## Composants

| Classe | Rôle |
|---|---|
| `HelloServlet` (`@WebServlet("/hello")`) | GET texte avec query param |
| `CounterServlet` (`@WebServlet("/count")`) | compteur par session |
| `AdminServlet` (`@WebServlet("/admin")` + `@ServletSecurity(rolesAllowed="admin")`) | protégé BASIC admin |
| `LoggingFilter` (`@WebFilter("/*")`) | journalise chaque requête |
| `StartupListener` (`@WebListener`) | installe le `SecurityProvider` démo au `contextInitialized` |

## Démarrage

```
mvn -pl vidocq-examples/vidocq-servlet-example -am package
java -p target/classes:... --module-path ... \
     -m vidocq.example.servlet/fr.vidocq.examples.servlet.ServletExampleApp
```

Plus simple via `mvn exec:exec` (à configurer) ou en packagé jar-with-deps.

## Requêtes d'exemple

```
curl http://localhost:8080/hello?name=alice
curl -c cookies.txt http://localhost:8080/count
curl -b cookies.txt http://localhost:8080/count       # incrémente
curl -u alice:admin http://localhost:8080/admin       # 200
curl -u bob:user http://localhost:8080/admin          # 403
curl http://localhost:8080/admin                       # 401 + WWW-Authenticate
```

## Configuration (`vidocq.properties`)

```
vidocq.chappe.listener.default.port=8080
vidocq.servlet.context-path=/
vidocq.servlet.session.timeout-seconds=600
```

## Utilisateurs de démo

| Login | Mot de passe | Rôles |
|---|---|---|
| `alice` | `admin` | `admin` |
| `bob` | `user` | `user` |

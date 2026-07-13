# Vidocq Runtime — Petstore Example

Démo bout-en-bout d'une API REST « petstore » (inspirée du petstore Swagger) construite
uniquement avec la stack Vidocq :

| Couche            | Brique                                                        |
|-------------------|---------------------------------------------------------------|
| Transport HTTP    | **Chappe** (HTTP/1.1 + H2/H3, zéro dépendance)                |
| REST / JAX-RS 4.0 | **Cassini** via `vidocq-runtime-cassini-rest-extension`       |
| Persistance       | **Mansart** Data 1.0 + Pool + Transactions sur **H2** in-memory |
| JSON-B / JSON-P   | **Champollion** (`champollion-jsonb` / `champollion-jsonp`)   |
| Config            | MicroProfile Config 3.1 via **Ravel** (opt-in)                |
| Packaging         | `vidocq-runtime-maven-plugin` → jlink + jpackage + Docker     |

Le modèle a trois entités **plates** — `Pet`, `Category`, `Tag` — reliées par des clés
étrangères et une table de jonction `pet_tags`. Mansart Data ne gère pas les associations JPA :
les relations sont **recomposées à la main dans `PetService`** (résolution/création de la
catégorie et des tags, assemblage du `PetView`).

## Comment l'application démarre

Il n'y a **pas de `main` dans les resources** : le point d'entrée est `PetstoreApp` —

```java
// io.vidocq.runtime.examples.petstore.PetstoreApp
public static void main(String[] args) {
    Vidocq.main(args);
}
```

`Vidocq.main(...)` démarre le `VidocqBootstrap`, qui découvre toutes les extensions présentes
sur le module path (via `ServiceLoader` / `provides VidocqExtension`) et les orchestre dans
l'ordre de priorité :

1. `mansart-pool` (200) — ouvre le pool H2 depuis `vidocq.pool.*` et publie le `DataSource`.
2. `mansart-data` (300) — câble les interfaces `@Repository` (impl générées par APT).
3. `cassini` (500) — monte les resources JAX-RS sur le listener Chappe.
4. `chappe-bootstrap` (10000) — démarre le listener sur `0.0.0.0:8080`.

`SchemaInitializer` (un `@Observes @Initialized(ApplicationScoped.class)`) crée le schéma et
les données de démo une fois le conteneur CDI prêt.

La classe d'entrée est déclarée une seule fois dans le `pom.xml` et consommée par tous les
goals de packaging :

```xml
<properties>
    <vidocq.mainModule>io.vidocq.runtime.examples.petstore</vidocq.mainModule>
    <vidocq.mainClass>io.vidocq.runtime.examples.petstore.PetstoreApp</vidocq.mainClass>
</properties>
```

## Build

Comme tout exemple, le petstore résout le runtime (et l'extension H2 ci-dessous) depuis le
dépôt local : construire d'abord le workspace runtime, puis l'exemple.

```bash
# depuis la racine du sous-projet vidocq/ : installe core + extensions (dont le repackage H2)
./mvnw -ntp -DskipTests install

# puis l'exemple seul
./mvnw -ntp -DskipTests -pl vidocq-runtime-examples/vidocq-runtime-petstore-example clean package
```

Le profil `dist` (actif par défaut) produit :

- `target/dist/` — image **jlink** autonome (JVM + app, ~zéro dépendance externe) ;
- `target/installer/petstore-app.app` — app-image **jpackage** native (`petstore-app` v1.0.0) ;
- `target/Dockerfile` — image Docker (distroless), non construite par défaut.

Pour un cycle rapide compile + tests sans packaging natif : `-P'!dist'`.

> **Pourquoi `vidocq-runtime-h2-module-repackaged` ?** H2 n'est qu'un *module automatique*
> (manifest `Automatic-Module-Name`, pas de `module-info`), ce que `jlink` refuse. L'artefact
> `io.vidocq.runtime.extensions.module.repackaged:vidocq-runtime-h2-module-repackaged` (dans
> `vidocq-runtime-extensions/vidocq-runtime-extensions-module-repackaged/`) republie H2 **à l'identique** en module Java Modules nommé
> `com.h2database` (`module-info` généré par `jdeps`, `provides java.sql.Driver with
> org.h2.Driver`), ce qui permet de produire le jpackage. Le petstore en dépend à la place du
> `com.h2database:h2` brut. Les classes H2 restent sous leur licence d'origine (MPL 2.0 / EPL 1.0,
> cf. `META-INF/NOTICE.txt`) ; seul le `module-info` ajouté est sous licence Vidocq.

## Lancer

```bash
# 1) Binaire jlink
./target/dist/bin/petstore-app

# 2) App-image jpackage (macOS)
./target/installer/petstore-app.app/Contents/MacOS/petstore-app

# 3) En dev, directement via Maven
../mvnw -pl vidocq-runtime-petstore-example vidocq:dev
```

Puis ouvrir l'UI : <http://localhost:8080/>

## API

| Méthode | Chemin                       | Effet                                            |
|---------|------------------------------|--------------------------------------------------|
| `GET`   | `/api/pets`                  | Liste les pets (filtre `?status=available\|pending\|sold`) |
| `GET`   | `/api/pets/{id}`             | Un pet (404 sinon)                               |
| `GET`   | `/api/pets/count`            | Nombre de pets (`text/plain`)                    |
| `POST`  | `/api/pets`                  | Crée un pet (catégorie + tags résolus/créés)     |
| `PUT`   | `/api/pets/{id}`             | Met à jour un pet et resynchronise ses tags      |
| `DELETE`| `/api/pets/{id}`             | Supprime un pet et ses liens                     |
| `GET` / `POST` | `/api/categories`     | Liste / crée une catégorie                       |
| `GET` / `POST` | `/api/tags`           | Liste / crée un tag                              |

Exemples :

```bash
curl http://localhost:8080/api/pets
curl 'http://localhost:8080/api/pets?status=available'

curl -X POST -H 'content-type: application/json' \
     -d '{"name":"Rex","category":"Dog","status":"available","price":120.0,"tags":["friendly","trained"]}' \
     http://localhost:8080/api/pets

curl -X PUT -H 'content-type: application/json' \
     -d '{"name":"Rex","category":"Dog","status":"sold","price":130.0,"tags":["trained"]}' \
     http://localhost:8080/api/pets/1

curl -X DELETE http://localhost:8080/api/pets/2
curl http://localhost:8080/api/categories
curl http://localhost:8080/api/tags
```

## Configuration

`src/main/resources/vidocq.properties` — listener Chappe, mount REST (`/api`), mount statique
(`/`), et le pool H2 (`jdbc:h2:mem:petstore;DB_CLOSE_DELAY=-1`). Base **in-memory** : le schéma
et les données de démo sont recréés à chaque démarrage par `SchemaInitializer`.

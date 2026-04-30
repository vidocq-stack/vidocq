# Packaging Vidocq — jlink, jpackage, Docker

Workflow complet pour packager une application Vidocq en artefact déployable
autonome via le `vidocq-mps-maven-plugin`. Trois cibles de packaging
disponibles, plus un mécanisme de surcharge de config externe.

| Cible | Goal | Output | Taille | Démarrage |
|-------|------|--------|--------|-----------|
| Image runtime jlink | `vidocq:jlink` | `target/dist/` (binaire + runtime Java embarqué) | ~40 MB | ~4 s |
| Bundle natif | `vidocq:jpackage` | `target/installer/<name>.app` (macOS), `.exe`/`.msi`/`.deb`/`.rpm` selon OS, ou app-image cross-platform | ~40 MB | ~1 s (CDS) |
| Image Docker | `vidocq:docker` | `target/Dockerfile` à builder via `docker build` | ~50 MB total | n/a |

Les trois reposent sur le **même** runtime jlink (cf. §Layout). `jpackage`
réutilise l'image produite par `jlink` ; `docker` la copie dans un container
distroless minimal.

---

## 1. Quick start sur `vidocq-mps-rest-example`

L'exemple `vidocq-mps-rest-example` est déjà câblé pour générer les trois
artefacts en une commande.

```sh
cd vidocq-mps-examples/vidocq-mps-rest-example
mvn package -DskipTests
```

Produit :

```
target/
├── dist/                               # image jlink autonome
│   ├── bin/todo-app                    # launcher binaire (pas de java requis)
│   ├── conf/                           # config surchargeable (cf. §5)
│   │   ├── vidocq.properties
│   │   └── logging.properties
│   ├── lib/                            # modules JPMS de l'app + JDK
│   ├── legal/                          # licences (jlink)
│   └── release                         # info build
├── installer/
│   └── todo-app.app/                   # bundle .app jpackage
└── Dockerfile                          # à passer à docker build
```

Lancement immédiat :

```sh
./target/dist/bin/todo-app
# → http://127.0.0.1:8080/        UI todo-list
# → http://127.0.0.1:8080/api/todos
```

---

## 2. Goal `vidocq:jlink`

### Rôle

Empaquette l'application + toutes ses dépendances modulaires + un runtime
Java minimal **dans un dossier autonome** (`target/dist/`). Le binaire
`bin/<launcher>` ne nécessite **aucune JVM pré-installée** sur la machine
cible.

### Mécanique interne

1. Stage tous les jars `compile`+`runtime` dans `target/jlink-mods/`.
2. Vérifie qu'aucun n'est un automatic module (sinon erreur claire).
3. `jdeps --print-module-deps` (ToolProvider) résout les modules JDK
   transitifs.
4. `jlink --module-path stage:$JAVA_HOME/jmods --add-modules <set>
   --launcher <name>=<module>/<mainClass> --strip-debug --compress=zip-6
   --no-header-files --no-man-pages --output target/dist`.
5. Copie `vidocq.properties` + `logging.properties` (et toute resource
   configurée) dans `dist/conf/`.

### Configuration

```xml
<plugin>
    <groupId>io.vidocq.mpserver</groupId>
    <artifactId>vidocq-mps-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>jlink</id>
            <goals><goal>jlink</goal></goals>
            <configuration>
                <mainModule>com.example.myapp</mainModule>          <!-- requis -->
                <mainClass>com.example.myapp.MainApp</mainClass>     <!-- requis -->
                <launcher>my-app</launcher>                          <!-- défaut: artifactId -->
                <distDir>${project.build.directory}/dist</distDir>   <!-- défaut -->
                <stripDebug>true</stripDebug>                        <!-- défaut: true -->
                <compress>zip-6</compress>                           <!-- zip-0..zip-9, défaut: zip-6 -->
                <includeResources>                                   <!-- défaut: vidocq.properties + logging.properties -->
                    <param>vidocq.properties</param>
                    <param>logging.properties</param>
                    <param>my-custom-config.yaml</param>
                </includeResources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Lancement

```sh
./target/dist/bin/my-app                                  # défaut
./target/dist/bin/my-app -Dfoo=bar                        # system properties
./target/dist/bin/my-app --module-path …                  # args supplémentaires (rares)
```

---

## 3. Goal `vidocq:jpackage`

### Rôle

Empaquette l'image jlink en un **bundle natif** par OS hôte :
`.app`/`.dmg`/`.pkg` (macOS), `.deb`/`.rpm` (Linux), `.exe`/`.msi`
(Windows), ou en app-image (dossier cross-platform sans installer).

### Mécanique interne

Réutilise `target/dist/` (sortie de `vidocq:jlink`) comme `--runtime-image`,
sans re-résoudre les modules. Délègue à `java.util.spi.ToolProvider("jpackage")`.

### Configuration

```xml
<execution>
    <id>jpackage</id>
    <goals><goal>jpackage</goal></goals>
    <configuration>
        <mainModule>com.example.myapp</mainModule>          <!-- requis -->
        <mainClass>com.example.myapp.MainApp</mainClass>     <!-- requis -->
        <appName>my-app</appName>                            <!-- défaut: artifactId -->
        <appVersion>1.0.0</appVersion>                       <!-- macOS exige 1.x.y, pas 0.x.y -->
        <type>app-image</type>                               <!-- app-image | dmg | pkg | deb | rpm | msi | exe -->
        <runtimeImage>${project.build.directory}/dist</runtimeImage>  <!-- défaut: sortie de jlink -->
        <installerDir>${project.build.directory}/installer</installerDir>
        <icon>src/main/resources/app.icns</icon>             <!-- optionnel; .icns/.ico/.png -->
        <vendor>${project.groupId}</vendor>                  <!-- défaut: groupId -->
        <description>${project.description}</description>    <!-- défaut: description du POM -->
    </configuration>
</execution>
```

### Type par défaut : `app-image`

- **macOS** : produit un dossier `<name>.app` exécutable (Mach-O) — pas
  d'installer signé. Idéal pour distribuer un binaire portable.
- **Linux** : dossier avec un launcher shell.
- **Windows** : dossier avec un `<name>.exe` Win32.

Pour un installer **distribuable signé**, fixer `<type>` à la valeur
appropriée (`dmg`, `deb`, etc.) et fournir l'outillage natif requis
(`pkgbuild`/`dpkg`/Wix). Vérifier les pré-requis dans la doc Oracle/OpenJDK
de `jpackage`.

### Versions

`jpackage` exige une version **strictement numérique** (`1.0.0`, `2.3.4`).
Le plugin strip automatiquement `-SNAPSHOT` et tout suffixe alphanumérique
de `${project.version}`. **macOS app-image refuse les versions commençant
par `0`** — fixer un `<appVersion>1.x.y</appVersion>` explicite si le
projet est en `0.x.x`.

---

## 4. Goal `vidocq:docker`

### Rôle

Génère un `Dockerfile` qui empaquette l'image runtime jlink dans un
container minimal. Pas de JRE supplémentaire requis — jlink contient son
propre runtime.

### Configuration

```xml
<execution>
    <id>docker</id>
    <goals><goal>docker</goal></goals>
    <configuration>
        <launcher>my-app</launcher>                          <!-- défaut: artifactId -->
        <imageTag>example/my-app:1.0.0</imageTag>            <!-- défaut: artifactId:version -->
        <baseImage>gcr.io/distroless/base-debian12:nonroot</baseImage>  <!-- défaut -->
        <exposedPort>8080</exposedPort>                      <!-- défaut: 8080 -->
        <runtimeImage>${project.build.directory}/dist</runtimeImage>
        <build>false</build>                                 <!-- défaut: false (génère seulement) -->
    </configuration>
</execution>
```

### Pourquoi `distroless/base-debian12:nonroot` par défaut ?

- ~20 MB, pas de shell, pas de package manager : surface d'attaque
  minimale.
- Pas de JRE — jlink fournit son propre runtime, l'inclure serait
  redondant.
- User non-root par défaut — sécurité par défaut conforme aux bonnes
  pratiques k8s.

Pour un debug interactif (shell, busybox), utiliser temporairement
`gcr.io/distroless/base-debian12:debug`.

### Build et run

```sh
# Build (le plugin ne le fait pas automatiquement par défaut)
docker build -t example/my-app:1.0.0 -f target/Dockerfile target/

# Run
docker run --rm -p 8080:8080 example/my-app:1.0.0

# Avec config surchargée (cf. §5)
docker run --rm -p 8080:8080 \
    -e VIDOCQ_CONFIG_DIR=/etc/myapp \
    -v $(pwd)/conf:/etc/myapp:ro \
    example/my-app:1.0.0
```

Pour invoquer `docker build` directement depuis Maven :
`<configuration><build>true</build></configuration>` ou
`-Dvidocq.docker.build=true`. Le plugin échoue proprement si le binaire
`docker` n'est pas disponible.

---

## 5. Surcharge de config externe (`ExternalFileConfigSource`)

Trois mécanismes par ordre de priorité décroissante :

```sh
# A. Propriété système (ordinal 400)
./bin/my-app -Dvidocq.chappe.listener.default.port=9090

# B. Variable d'environnement (ordinal 300)
VIDOCQ_CONFIG_DIR=/etc/my-app ./bin/my-app
# → lit /etc/my-app/vidocq.properties

# C. Fichier externe (ordinal 250 — nouveau)
# Cherche dans l'ordre :
#   1. ${vidocq.config.dir}/vidocq.properties        (system prop)
#   2. ${VIDOCQ_CONFIG_DIR}/vidocq.properties        (env var)
#   3. ${java.home}/conf/vidocq.properties           (convention jlink)
#   4. ./conf/vidocq.properties                      (working dir)
```

### Convention `${java.home}/conf/vidocq.properties`

En image jlink, `java.home` pointe sur le runtime image (= `dist/`). Le
goal `vidocq:jlink` copie le `vidocq.properties` du classpath dans
`dist/conf/`. Donc :

```sh
# Édition à la volée sans recompilation :
echo "vidocq.chappe.listener.default.port=9090" >> target/dist/conf/vidocq.properties
./target/dist/bin/my-app   # tourne désormais sur 9090
```

C'est exactement le cas d'usage "ops modifient la config d'une release
sans rebuilder".

### Diagnostic

Au boot, le log inclut une ligne :

```
INFO ExternalFileConfigSource: Loaded external config from /path/to/vidocq.properties (3 entries)
```

Si aucun fichier n'est trouvé, la source est silencieusement absente et
seule la config classpath (`PropertiesFileConfigSource`, ordinal 100)
s'applique.

### Hiérarchie complète des `ConfigSource`

| Ordinal | Source | Description |
|---------|--------|-------------|
| 400 | `SystemPropertiesConfigSource` | `-Dkey=value` |
| 300 | `EnvConfigSource` | Variables d'environnement |
| **250** | **`ExternalFileConfigSource`** | **Fichier externe surchargeable** |
| 100 | `PropertiesFileConfigSource` | `vidocq.properties` du classpath |

---

## 6. Pré-requis et bonnes pratiques

### 6.1 Tous les jars doivent être des modules JPMS nommés

`jlink` rejette les automatic modules. Le goal détecte le cas et échoue
proprement avec :

```
jlink ne supporte pas les automatic modules : my.legacy.lib (file:///…/legacy.jar).
Convertis ces jars en vrais modules JPMS (ajout d'un module-info.java).
```

Pour ton propre projet :

- Ajouter un `module-info.java` dans le module applicatif.
- Pour les libs tierces non modulaires : utiliser `jdeps --generate-module-info`,
  ou demander une release modulaire upstream, ou re-packager.

### 6.2 Records sérialisés via REST

Yasson 3.0.4 + records + module-path strict ne désérialise pas le canonical
constructor : les champs `String` reviennent `null` après round-trip
POST → GET. Workaround : factory annotée `@JsonbCreator` :

```java
public record Todo(long id, String title, boolean done) {
    @JsonbCreator
    public static Todo create(@JsonbProperty("id") long id,
                              @JsonbProperty("title") String title,
                              @JsonbProperty("done") boolean done) {
        return new Todo(id, title, done);
    }
}
```

À retirer une fois `cassini-jsonb` maison livré
(cf. [cassini/JSON-ROADMAP.md](../cassini/JSON-ROADMAP.md)).

### 6.3 Reflection JAX-RS / JSON-B

Les ressources, providers, modèles de données doivent être dans un package
**ouvert** :

```java
module com.example.myapp {
    requires jakarta.ws.rs;
    requires jakarta.json.bind;
    requires io.vidocq.mpserver.core;
    requires io.vidocq.mpserver.ext.rest.cassini;

    opens com.example.myapp;       // JAX-RS + JSON-B reflection
}
```

### 6.3.1 Custom `java.util.logging` Handler

Si ton app déclare un handler logging custom (`StdoutHandler`,
`CompactFormatter`, etc.) référencé dans `logging.properties` via
`handlers = com.example.myapp.logging.StdoutHandler`, il faut **exporter**
le package à `java.logging` — sinon `LogManager.createLoggerHandlers`
échoue par `IllegalAccessException` au boot :

```java
module com.example.myapp {
    requires java.logging;
    // …
    exports com.example.myapp.logging to java.logging;
}
```

`exports … to java.logging` (ciblé) est suffisant — pas besoin d'`opens`,
puisque `Class.newInstance()` n'utilise que le constructeur public no-arg.

### 6.4 macOS app-image et version

`jpackage --type app-image` sur macOS exige `appVersion` commençant par `1`
ou plus. Un projet `0.1.0-SNAPSHOT` doit fixer un `<appVersion>1.0.0</appVersion>`
explicite.

### 6.5 Ports dans Docker

Le `EXPOSE` du Dockerfile généré pointe sur le port configuré
(`<exposedPort>`, défaut 8080). S'assurer que `vidocq.chappe.listener.default.port`
correspond, sinon le mapping `-p` est inopérant.

---

## 7. Layout des artefacts produits

### Image jlink (`target/dist/`)

```
dist/
├── bin/
│   ├── java                        # binaire JVM réduit
│   ├── keytool                     # utilitaire JDK
│   └── <launcher>                  # ← launcher de l'app (entrypoint)
├── conf/
│   ├── jaxp.properties             # config JDK
│   ├── logging.properties          # ← copié par vidocq:jlink
│   ├── net.properties
│   ├── security/                   # truststore, policies
│   └── vidocq.properties           # ← copié par vidocq:jlink, surchargeable
├── lib/                            # modules JPMS (app + JDK + libs)
│   ├── modules                     # archive jimage des modules
│   ├── jrt-fs.jar
│   └── …
├── legal/                          # licences des modules JDK
└── release                         # version JDK + modules embarqués
```

### Bundle macOS (`target/installer/<name>.app/`)

```
<name>.app/
└── Contents/
    ├── Info.plist                  # métadonnées Bundle
    ├── PkgInfo
    ├── MacOS/
    │   └── <name>                  # launcher Mach-O ARM64/x86_64
    ├── app/                        # config additionnelle (vide ici)
    ├── Resources/                  # icônes, assets
    ├── runtime/                    # ← image jlink intégrée
    │   └── Contents/Home/          # = target/dist/ structure
    │       ├── bin/, conf/, lib/, legal/, release
    └── _CodeSignature/             # signature ad-hoc (à re-signer pour distrib)
```

### Image Docker

```
gcr.io/distroless/base-debian12:nonroot
└── /opt/app/                       # = COPY target/dist/
    ├── bin/<launcher>              # entrypoint
    ├── conf/, lib/, legal/, release
WORKDIR /opt/app
EXPOSE 8080
ENTRYPOINT ["/opt/app/bin/<launcher>"]
```

---

## 8. Limitations connues

| # | Limitation | Workaround |
|---|------------|------------|
| 1 | Toute dépendance non-modulaire bloque `jlink` | Ajouter `module-info.java` ou utiliser `jdeps --generate-module-info` |
| 2 | Yasson + records → `title=null` round-trip POST/GET en module-path | Factory `@JsonbCreator` (cf. §6.2). Sera réglé par `cassini-jsonb` maison. |
| 3 | macOS `app-image` refuse `appVersion=0.x.y` | Fixer `<appVersion>1.0.0</appVersion>` |
| 4 | `--strip-debug` retire `LineNumberTable` (stack-traces moins lisibles) | `<stripDebug>false</stripDebug>` en dev, `true` en prod |
| 5 | Pas de fallback fat-jar (uber-jar via shade-plugin) | Backlog `package-fatjar` ; jlink + docker couvrent ~95 % des cas |
| 6 | `jpackage` `--type=dmg`/`deb`/`msi` requiert un outillage natif (`pkgbuild`, `dpkg`, Wix) | Utiliser `app-image` qui n'a aucun pré-requis natif, ou installer l'outillage en CI |

---

## 9. Roadmap

- **`package-fatjar`** (backlog) — fallback shade-plugin pour les
  déploiements legacy ne supportant pas le module-path.
- **`cassini-jsonb` / `cassini-jsonp`** maison
  ([roadmap](../cassini/JSON-ROADMAP.md)) — supprimera la friction
  records + Yasson.
- **CDS pré-généré** dans `vidocq:jlink` (`--generate-cds-archive`) — gain
  de 100-300 ms supplémentaires au démarrage.
- **Image multi-arch Docker** (buildx, `linux/amd64`+`linux/arm64`) — utile
  pour la production multi-archi.
- **Sign macOS / Windows installers** — workflow `codesign` + Apple
  notarization, signtool Win32 ; pas inclus dans le plugin pour l'instant.

---

## 10. Référence rapide

```sh
# Build complet (génère dist + .app + Dockerfile)
mvn package -DskipTests

# Lancer image jlink directement
./target/dist/bin/<launcher>

# Lancer bundle macOS
open target/installer/<name>.app
# ou en mode CLI pour voir les logs :
./target/installer/<name>.app/Contents/MacOS/<launcher>

# Lancer Docker
docker build -t <tag> -f target/Dockerfile target/
docker run --rm -p 8080:8080 <tag>

# Surcharger la config sans recompiler
echo "vidocq.foo=bar" >> target/dist/conf/vidocq.properties
./target/dist/bin/<launcher>

# Override via env (Docker/k8s friendly)
VIDOCQ_CONFIG_DIR=/etc/myapp ./target/dist/bin/<launcher>

# Override via system property
./target/dist/bin/<launcher> -Dvidocq.foo=bar
```

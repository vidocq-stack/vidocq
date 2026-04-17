# vidocq-servlet-chappe-tck-runner

Harness de conformance Jakarta Servlet 6.1 pour `vidocq-servlet-chappe-extension`.

Fournit :

1. un **harness programmatique** (`ServletTestHarness`) qui démarre un serveur Chappe local avec notre bridge servlet, utilisable depuis JUnit sans container externe ;
2. une **suite de tests de conformance** organisée par chapitre de la spec, exécutée à chaque build ;
3. un **point d'entrée** documenté pour brancher le TCK officiel Eclipse Foundation quand il sera intégré.

## Harness

```java
try (var h = ServletTestHarness.builder()
        .servlet("/hello", new HelloServlet())
        .filter("/*", new LoggingFilter())
        .securityProvider(myProvider)
        .start()) {
    HttpResponse<String> r = h.get("/hello?name=alice");
    assertEquals(200, r.statusCode());
}
```

Fonctionnalités :

- `servlet(urlPattern, servlet)`, `filter(urlPattern, filter)`, `listener(listener)` — contribution programmatique
- `errorPage(status, location)`, `errorPage(Class<? extends Throwable>, location)`
- `contextPath(path)`, `securityProvider(provider)`
- Port libre auto-alloué avec retry absorbant les courses avec Chappe bind
- `AutoCloseable` : `close()` arrête le serveur

## Conformance tests

Les tests couvrent les chapitres principaux de la spec :

| Chapitre | Fichier |
|---|---|
| §12 URL patterns + §9 RequestDispatcher | `ConformanceDispatcherTest` |
| §7 HttpSession | `ConformanceSessionTest` |
| §6 Filters | `ConformanceFilterTest` |
| §2.3.3.3 Async | `ConformanceAsyncTest` |
| §13 Security | `ConformanceSecurityTest` |

Ils ne remplacent pas le TCK officiel mais reproduisent les scénarios typiques et donnent une couverture exécutable en CI sans licence externe.

## TCK officiel Jakarta Servlet 6.1

### Licence

Le TCK est distribué sous **EFTL** (Eclipse Foundation Technology Compatibility Kit License). Une signature de la license est requise pour exécuter la suite et publier les résultats.

### Obtention

Repository : https://github.com/jakartaee/servlet (répertoire `tck/`), tag correspondant à Jakarta EE 11. Téléchargement manuel en ZIP ou via `git clone`.

Les artifacts nécessaires :

- `jakarta-servlet-tck-<version>.zip` — suite de tests
- `jakarta.tck:servlet-api-tck-tests` — classes Java des tests
- `sigtest-maven-plugin` — signature tests binaires

Aucun de ces artifacts n'est publié sur Maven Central ; il faut les installer localement ou les héberger dans un dépôt interne.

### Adapter Arquillian

Le TCK s'appuie sur `arquillian-core` et requiert un `DeployableContainer` qui :

1. Accepte un `ShrinkWrap` `WebArchive` (WAR packagé)
2. Extrait les classes servlet/filter/listener + `web.xml`
3. Déploie via `WebXmlContributor` + instanciation par `Class.forName`
4. Expose l'URL racine du contexte HTTP
5. Arrête proprement à la fin du test

Squelette envisagé :

```java
public final class VidocqServletArquillianContainer
        implements DeployableContainer<VidocqServletContainerConfig> {
    public ProtocolMetaData deploy(Archive<?> archive) { ... }
    public void undeploy(Archive<?> archive) { ... }
    public void start() { ... }
    public void stop() { ... }
}
```

Le harness `ServletTestHarness` couvre 80 % du travail — reste le packaging du WAR et le mapping classes JAR → classloader isolé.

### Signature tests

`sigtest-maven-plugin` vérifie qu'aucune dépendance API (`jakarta.servlet.*`) n'a dérivé de la version ratifiée. À activer en profil `-Psignature` avec :

```xml
<plugin>
  <groupId>org.netbeans.tools</groupId>
  <artifactId>sigtest-maven-plugin</artifactId>
  <version>1.7</version>
  <configuration>
    <packages>jakarta.servlet,jakarta.servlet.http,jakarta.servlet.annotation</packages>
    <sigfile>${project.basedir}/src/sigtest/jakarta.servlet-6.1.sig</sigfile>
  </configuration>
</plugin>
```

Le fichier `.sig` est généré par le TCK officiel.

### Exclusions légitimes documentées

Fonctionnalités hors scope actuel à exclure de la suite TCK :

- Tests JSP (spec distincte, pas un requis Servlet standalone)
- Jakarta Authentication complète — seul BASIC est livré, FORM / DIGEST / CLIENT-CERT sont reportés
- Clustering de sessions
- Full JNDI (`java:comp/env`)

Ces exclusions seront listées dans `tck-exclusions.md` lors de l'intégration.

### Roadmap d'activation

1. Télécharger le TCK et installer les artifacts en repo local
2. Créer `VidocqServletArquillianContainer` s'appuyant sur `ServletTestHarness`
3. Ajouter un profil Maven `-Ptck-official` qui inclut `jakarta.tck:servlet-api-tck-tests`
4. Documenter les exclusions et lancer `mvn -Ptck-official verify`
5. Reporter les résultats de conformité (nombre de tests passants / total)

### État de l'intégration TCK officielle

Infrastructure posée et opérationnelle jusqu'à l'appel de `getTestArchive()` :

- ✅ TCK Jakarta Servlet 6.1.0 téléchargé depuis Eclipse Foundation
  (`https://download.eclipse.org/jakartaee/servlet/6.1/jakarta-servlet-tck-6.1.0.zip`)
- ✅ Artifacts installés localement : `jakarta.tck:servlet-tck-runtime:6.1.0`,
  `jakarta.tck:servlet-tck-util:6.1.0`, `jakarta.tck:servlet-tck:6.1.0` (pom)
- ✅ Profil Maven `-Ptck-official` ajoute les deps Arquillian + ShrinkWrap + slf4j
- ✅ `VidocqDeployableContainer` + `VidocqContainerConfiguration` +
  `VidocqContainerExtension` (SPI `LoadableExtension`) compilent
- ✅ Surefire configuré : `<dependenciesToScan>`, `argLine --add-opens`,
  `workingDirectory = target/tck-workdir`
- ✅ Pom auxiliaire Maven 4.0 copié dans `target/tck-workdir/pom.xml`
- ✅ Arquillian détecte notre container, l'instancie, appelle
  `getTestArchive()` du test TCK

**Point de blocage actuel** : ShrinkWrap Maven Resolver 1.2.6 utilise un
`ClasspathWorkspaceReader` qui scanne le reactor Maven courant. Il tombe sur
le pom principal (Model Version **4.1.0** avec parent inherited) et échoue :

```
Bad artifact coordinates fr.vidocq.vidocq:vidocq-servlet-chappe-tck-runner:jar:,
expected format is <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>
```

ShrinkWrap 1.2.6 ne supporte pas Maven 4.1.

**Contournement possibles** :

1. Extraire le tck-runner en projet Maven standalone hors du reactor principal,
   avec Model Version 4.0.0 et dépendances gelées sur des artifacts installés.
2. Attendre une version de ShrinkWrap Resolver supportant Maven 4.1
   (upstream : https://github.com/shrinkwrap/resolver).
3. Forker ShrinkWrap et patcher `ClasspathWorkspaceReader.createFoundArtifact`
   pour gérer le format 4.1.

Commande actuelle (stoppe sur le blocage ci-dessus) :
```
mvn -pl vidocq-core-extensions/vidocq-servlet-chappe-tck-runner test \
    -Ptck-official -Dtest='ServletTests#DoDestroyedTest' \
    -DfailIfNoTests=false
```

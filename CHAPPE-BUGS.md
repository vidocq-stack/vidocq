# Bugs Chappe identifiés

> Même format que `VAUBAN-BUGS.md` : chaque entrée décrit un comportement
> anormal observé depuis `vidocq-rest-cassini-extension` pendant le TCK
> Jakarta REST 4.0. Les sources Chappe sont dans
> `/Users/yblazart/projects/perso/vidocq/chappe/`.

---

## 1. ~~`Request.attribute(String, Object)` default method — non persistant~~ FIXÉ upstream

**Symptôme initial** : `request.attribute("cassini.formCache", parsedForm)`
suivi de `request.attribute("cassini.formCache")` retournait `null` dans
un JAR chappe-api 0.1.0-SNAPSHOT publié au repo local.

**Status** : les sources actuelles (`chappe-http/.../HttpRequestImpl.java`
lignes 69 + 202-212) ont une `LinkedHashMap attributes` backing et les
overrides `attribute(String)` / `attribute(String, Object)` qui lisent
et écrivent dessus. Le JAR local `~/.m2/repository/fr/vidocq/chappe/
chappe-api/0.1.0-SNAPSHOT/` avait probablement été généré avant ce fix.

**Action côté Cassini** : le workaround ThreadLocal reste en place pour
l'instant (évite de dépendre d'une version précise de Chappe). À retirer
quand un bump Chappe sera effectué et que Cassini pourra se reposer sur
la persistance des attributs.

---

## 2. Risque de StackOverflowError sur `NewCookie.toString()` côté Cassini

**Symptôme** (côté Cassini, pas un bug Chappe à proprement parler — à
documenter si on trouve équivalent Chappe) : TCK Cookie tests
`The server 127.0.0.1 failed to respond` → investigation a révélé une
récursion potentielle si le `RuntimeDelegate` retourne un `HeaderDelegate`
générique dont `toString(value)` appelle `value.toString()`.

Fixé côté Cassini par des `HeaderDelegate` dédiés (NewCookie, Cookie,
EntityTag, CacheControl, Link, Date). Noté ici pour mémoire : si Chappe
un jour expose des types typés avec leur propre `toString`, prévoir la
même précaution.

---

## 3. TCK Cookie — client Apache HttpClient 3.x coupe la connexion

**Symptôme** : les tests `ee.rs.cookieparam.JAXRSClientIT` et
`ee.rs.beanparam.cookie.plain` échouent systématiquement avec
`org.apache.commons.httpclient.NoHttpResponseException: The server
127.0.0.1 failed to respond` au SECOND appel HTTP.

Le premier appel fonctionne (le serveur renvoie une réponse avec
`Set-Cookie`). Le client (Apache HttpClient 3.x, vieux — utilisé par
le TCK pour ces tests-là spécifiquement) réutilise la connexion HTTP
en keep-alive et n'arrive pas à lire la response status line.

**Pistes** :
- Chappe insère-t-il correctement le séparateur `\r\n\r\n` entre les
  frames HTTP successives sur la même connexion keep-alive ?
- Le `Content-Length` est-il exact côté serveur quand le corps est
  produit par un `MessageBodyWriter` ? (cf. CassiniWriterInterceptorContext
  qui écrit dans un `ByteArrayOutputStream` puis `Body.of(bos.toByteArray())`)
- Y a-t-il un timing où Chappe ferme prématurément la connexion ?

À reproduire avec `curl --http1.1 -v -b name=x http://.../CookieParamTest`
deux fois sur la même connexion.

---

## 5. `Request.query()` — retourne null alors que le rawUri contient un query string

**Symptôme** : pour un `POST /ctx/resource/queryfield?bpeQuery=FIRST&innerQuery=SECOND`
envoyé par le client TCK, `request.query()` depuis Cassini retourne
`null`. Cassini ne peut donc pas extraire les `@QueryParam`.

Diag ajouté dans FieldInjector : `query=null`, `parsedKeys=[]` → le
bean `bpeQuery`/`innerQuery` restent null, le TCK voit `Anythingnullnull`
au lieu de `Anything&bpeQuery=FIRST&innerQuery=SECOND`.

**Cas déclencheur précis** : routes arrivant via un `Handler` wrapper
qui réécrit `path()` (cf. `CassiniTestHarness.ContextStrippingHandler`
qui stripe le contextPath). Quand seul `path()` est surchargé,
Chappe a peut-être un parser qui ne restitue pas `query()` ensuite.
À vérifier côté `HttpRequestImpl.ensurePathQueryParsed` et interaction
avec les setters.

**Workaround Cassini** : fallback sur `request.uri().getRawQuery()`.
Malheureusement inefficace si `request.uri()` a aussi été amputé de
la query par le même parser.

**Impact TCK** : ~10 tests beanparam.plain qui échouent sur des
`@QueryParam` injectés dans des fields de `@BeanParam`.

---

## 4. `Request.uri()` — authority parfois absente

**Symptôme** : dans l'Invoker Cassini, `request.uri().getAuthority()` retourne
`null` sur certains paths, ce qui oblige à reconstruire le baseUri à partir
du header `Host` (fallback 127.0.0.1).

**Cas déclencheur** : non confirmé précisément — peut-être lorsque la
request-line HTTP est path-only (RFC 9112 §3.2.1 `origin-form`), ce qui
est le comportement normal du client HTTP 1.1 avec un `Host` header
séparé.

**Attente** : `Request.uri()` devrait reconstruire une URI absolue à partir
de `Host` pour que `uri.getAuthority()` soit toujours renseigné. Aujourd'hui
le caller doit le faire lui-même (cf. `Invoker.invoke` dans Cassini).

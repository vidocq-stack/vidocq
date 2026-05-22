# Bugs Chappe identifiés

> Même format que `VAUBAN-BUGS.md` : chaque entrée décrit un comportement
> anormal observé depuis `vidocq-runtime-cassini-rest-extension` pendant le TCK
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

**Investigation 2026-04-21** : inspection de `HttpConnection` (boucle
keep-alive : `parse → body → dispatch → write → reset → loop`) et
`HttpResponseWriter` (séparateurs CRLF, Content-Length directement
sérialisé dans le buffer). La logique est conforme et `KeepAliveTest`
passe (2 tests). Pas de cause racine identifiée hors contexte TCK ;
probablement une incompatibilité spécifique à Apache HttpClient 3.x
(client très ancien) qui n'attend pas la réponse comme un client
moderne. À reprendre si on arrive à reproduire hors TCK avec curl.

---

## 6. TCK POST + query string — Apache HttpClient 3.x perd la query côté wire

**Symptôme** : le TCK `ee.rs.beanparam.plain.JAXRSClientIT#queryParamOnFieldTest`
envoie (selon le log Arquillian) :
```
POST http://.../queryfield?bpeQuery=FIRST&innerQuery=SECOND
body=Anything
```
Côté serveur, `request.rawUri = /queryfield` (sans query), donc
`request.query() = null` et `request.uri() = http://host/queryfield`
(sans `?...`).

**Vérifié** : Chappe parse correctement la request-line (tests
Chappe + ajouts côté Cassini confirmant `rawUri` retourne ce qui a
été reçu sur le wire). Les composants Cassini (ContextStrippingHandler,
wrappers) délèguent `query()` fidèlement.

**Cause présumée** : Apache HttpClient 3.x — utilisé par la couche
`webclient.http.HttpRequest` du TCK — réécrit la request-line pour
POST en stripant la query string et ne l'incorpore pas au body non
plus (contrairement à un HttpClient 4.x/5.x moderne qui préserverait
la query). La TCK client layer est commune.io.JAXRSCommonClient ->
HttpRequest (org.apache.commons.httpclient.HttpMethodBase 3.1).

**Impact** : ~10 tests `beanparam.plain.*ParamOnField*Test` qui
dépendent de query string sur POST. Workaround côté Cassini :
fallback sur `request.uri().getRawQuery()` — mais inefficace ici
puisque l'URI n'a pas la query non plus.

**Non actionnable côté serveur** : le client TCK ne nous envoie
simplement pas les query params. À reprendre si une config TCK ou
un patch HttpClient 3.x permet de préserver.

---

## 5. ~~`Request.query()` — retourne null alors que le rawUri contient un query string~~ NON REPRODUIT

**Symptôme initial** : pour un `POST /ctx/resource/queryfield?bpeQuery=FIRST&innerQuery=SECOND`
envoyé par le client TCK, `request.query()` depuis Cassini retourne
`null`.

**Investigation 2026-04-21** : deux tests ciblés ajoutés côté Chappe
(`ExtensionSpiTest#mountPreservesQueryString` et
`#externalWrapperPreservesQueryString`) couvrent exactement ce scénario :
route montée via `Router.mount("/ctx", ...)` et wrapper externe qui ne
surcharge que `path()` (reproduisant `ContextStrippingHandler`). Les
deux passent — `query()` est correctement restitué dans les deux cas.
`HttpRequestImpl.ensurePathQueryParsed` parse `rawUri` indépendamment
de toute réécriture downstream, et les wrappers (mount() et
`ContextStrippingHandler`) délèguent bien `query()` à la requête
d'origine.

**Status** : probablement un JAR Chappe stale (comme bug #1). Après
un bump Chappe, le workaround Cassini (`request.uri().getRawQuery()`
en fallback dans `FieldInjector.parsedQueryParams` et
`ParamExtractor.parsedQueryFromRequest`) peut être retiré.

---

## 4. ~~`Request.uri()` — authority parfois absente~~ FIXÉ upstream 2026-04-21

**Symptôme** : dans l'Invoker Cassini, `request.uri().getAuthority()` retourne
`null`, ce qui oblige à reconstruire le baseUri à partir du header `Host`
(fallback 127.0.0.1).

**Cause confirmée** : `HttpRequestImpl.uri()` faisait `URI.create(rawUri)`
sur un `rawUri` origin-form (RFC 9112 §3.2.1, la forme normale en
HTTP/1.1), donc `/foo?bar=1` — ce qui produit une URI avec `authority=null`,
`scheme=null`, `host=null`.

**Fix** : `HttpRequestImpl.uri()` reconstruit désormais une URI absolue
`scheme://host+rawUri` en lisant le header `Host`. Si le `rawUri` est
déjà absolute-form (proxy), il est utilisé tel quel. Si `Host` est absent,
fallback sur `URI.create(rawUri)` (comportement historique).

- Source : `chappe-http/src/main/java/fr/vidocq/chappe/http/HttpRequestImpl.java`
  — méthode `buildUri()`.
- Test de non-régression : `ExtensionSpiTest#requestUriHasAuthorityFromHostHeader`.
- 65/65 tests Chappe passent après le fix.

**Action côté Cassini** : le fallback `Host` dans `Invoker` reste en place
(rétro-compat avec Chappe anciens), mais deviendra redondant après bump.

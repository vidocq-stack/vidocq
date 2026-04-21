# Bugs Chappe identifiés

> Même format que `VAUBAN-BUGS.md` : chaque entrée décrit un comportement
> anormal observé depuis `vidocq-rest-cassini-extension` pendant le TCK
> Jakarta REST 4.0. Les sources Chappe sont dans
> `/Users/yblazart/projects/perso/vidocq/chappe/`.

---

## 1. `Request.attribute(String, Object)` default method — non persistant

**Symptôme** : `request.attribute("cassini.formCache", parsedForm)` suivi de
`request.attribute("cassini.formCache")` retourne `null` — l'attribut écrit
n'est pas relu.

**Contexte** : le runtime REST veut cacher des objets par-requête (corps
form-urlencoded parsé, etc.) pour éviter de les recalculer N fois pendant
l'injection des @FormParam / @BeanParam. L'API Chappe expose deux default
methods :

```java
// chappe-api Request.java (approx.)
public default java.lang.Object attribute(java.lang.String);
public default fr.vidocq.chappe.api.Request attribute(java.lang.String, java.lang.Object);
```

Sans override concret dans `DefaultRequest` (ou l'impl HTTP), ces méthodes
se comportent comme des no-op : le get retourne toujours `null`, le set est
silencieusement ignoré.

**Workaround Cassini** : stockage en `ThreadLocal` dans `FieldInjector`
+ reset en `Invoker.invoke` finally. Non idéal (impacte les requêtes sur
virtual threads et fait traîner un état statique par classloader).

**Fix suggéré** : backing `Map<String, Object>` dans l'impl Chappe de
`Request` (ConcurrentHashMap initialisée paresseusement), nettoyée au retour
du `HttpConnection.run()`.

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

# Chappe bugs identified

> Same format as `VAUBAN-BUGS.md`: each entry describes abnormal behaviour
> observed from `vidocq-runtime-cassini-rest-extension` during the Jakarta REST
> 4.0 TCK. Chappe sources are in
> `/Users/yblazart/projects/perso/vidocq/chappe/`.

---

## 1. ~~`Request.attribute(String, Object)` default method — not persistent~~ FIXED upstream

**Initial symptom**: `request.attribute("cassini.formCache", parsedForm)`
followed by `request.attribute("cassini.formCache")` returned `null` in
a chappe-api 0.1.0-SNAPSHOT JAR published to the local repo.

**Status**: current sources (`chappe-http/.../HttpRequestImpl.java`
lines 69 + 202-212) have a `LinkedHashMap attributes` backing and the
`attribute(String)` / `attribute(String, Object)` overrides that read and write
to it. The local JAR in `~/.m2/repository/fr/vidocq/chappe/
chappe-api/0.1.0-SNAPSHOT/` was probably generated before this fix.

**Action on Cassini side**: the ThreadLocal workaround remains in place for now
(avoids depending on a specific Chappe version). To be removed when a Chappe
bump is done and Cassini can rely on attribute persistence.

---

## 2. Risk of StackOverflowError on `NewCookie.toString()` in Cassini

**Symptom** (Cassini side, not strictly a Chappe bug — documenting in case a
Chappe equivalent is found): TCK Cookie tests
`The server 127.0.0.1 failed to respond` → investigation revealed a potential
recursion if the `RuntimeDelegate` returns a generic `HeaderDelegate` whose
`toString(value)` calls `value.toString()`.

Fixed on the Cassini side with dedicated `HeaderDelegate` implementations
(NewCookie, Cookie, EntityTag, CacheControl, Link, Date). Noted here as a
reminder: if Chappe ever exposes typed types with their own `toString`, apply
the same precaution.

---

## 3. TCK Cookie — Apache HttpClient 3.x drops the connection

**Symptom**: `ee.rs.cookieparam.JAXRSClientIT` and
`ee.rs.beanparam.cookie.plain` tests fail systematically with
`org.apache.commons.httpclient.NoHttpResponseException: The server
127.0.0.1 failed to respond` on the SECOND HTTP call.

The first call works (the server returns a response with `Set-Cookie`). The
client (Apache HttpClient 3.x, very old — used by the TCK for these specific
tests) reuses the HTTP connection in keep-alive mode and fails to read the
response status line.

**Investigation leads**:
- Does Chappe correctly insert the `\r\n\r\n` separator between successive HTTP
  frames on the same keep-alive connection?
- Is the `Content-Length` correct on the server side when the body is produced
  by a `MessageBodyWriter`? (cf. CassiniWriterInterceptorContext which writes
  into a `ByteArrayOutputStream` then `Body.of(bos.toByteArray())`)
- Is there a timing where Chappe closes the connection prematurely?

To reproduce with `curl --http1.1 -v -b name=x http://.../CookieParamTest`
twice on the same connection.

**Investigation 2026-04-21**: inspection of `HttpConnection` (keep-alive loop:
`parse → body → dispatch → write → reset → loop`) and `HttpResponseWriter`
(CRLF separators, Content-Length serialized directly into the buffer). The logic
is compliant and `KeepAliveTest` passes (2 tests). No root cause identified
outside TCK context; likely a compatibility issue specific to Apache HttpClient
3.x (very old client) that does not wait for the response like a modern client.
To revisit if we can reproduce outside the TCK with curl.

---

## 6. TCK POST + query string — Apache HttpClient 3.x drops the query string on the wire

**Symptom**: the TCK `ee.rs.beanparam.plain.JAXRSClientIT#queryParamOnFieldTest`
sends (according to the Arquillian log):
```
POST http://.../queryfield?bpeQuery=FIRST&innerQuery=SECOND
body=Anything
```
On the server side, `request.rawUri = /queryfield` (no query), so
`request.query() = null` and `request.uri() = http://host/queryfield`
(without `?...`).

**Verified**: Chappe parses the request-line correctly (Chappe tests + Cassini
additions confirming `rawUri` returns what was received on the wire). The Cassini
components (ContextStrippingHandler, wrappers) faithfully delegate `query()`.

**Presumed cause**: Apache HttpClient 3.x — used by the TCK's
`webclient.http.HttpRequest` layer — rewrites the request-line for POST by
stripping the query string and does not include it in the body either (unlike a
modern HttpClient 4.x/5.x which would preserve the query). The TCK client layer
is `common.io.JAXRSCommonClient` → `HttpRequest` (org.apache.commons.httpclient.HttpMethodBase 3.1).

**Impact**: ~10 `beanparam.plain.*ParamOnField*Test` tests that depend on query
string on POST. Workaround on Cassini side: fallback on
`request.uri().getRawQuery()` — but ineffective here since the URI has no query
either.

**Not actionable server-side**: the TCK client simply does not send the query
params to us. To revisit if a TCK config or HttpClient 3.x patch can preserve
them.

---

## 5. ~~`Request.query()` — returns null when rawUri contains a query string~~ NOT REPRODUCED

**Initial symptom**: for a `POST /ctx/resource/queryfield?bpeQuery=FIRST&innerQuery=SECOND`
sent by the TCK client, `request.query()` from Cassini returned `null`.

**Investigation 2026-04-21**: two targeted tests added on the Chappe side
(`ExtensionSpiTest#mountPreservesQueryString` and
`#externalWrapperPreservesQueryString`) cover exactly this scenario: route
mounted via `Router.mount("/ctx", ...)` and external wrapper that only overrides
`path()` (reproducing `ContextStrippingHandler`). Both pass — `query()` is
correctly returned in both cases. `HttpRequestImpl.ensurePathQueryParsed` parses
`rawUri` independently of any downstream rewrite, and the wrappers (mount() and
`ContextStrippingHandler`) properly delegate `query()` to the original request.

**Status**: likely a stale Chappe JAR (like bug #1). After a Chappe bump, the
Cassini workaround (`request.uri().getRawQuery()` fallback in
`FieldInjector.parsedQueryParams` and
`ParamExtractor.parsedQueryFromRequest`) can be removed.

---

## 4. ~~`Request.uri()` — authority sometimes absent~~ FIXED upstream 2026-04-21

**Symptom**: in the Cassini Invoker, `request.uri().getAuthority()` returned
`null`, forcing the baseUri to be reconstructed from the `Host` header
(fallback 127.0.0.1).

**Confirmed cause**: `HttpRequestImpl.uri()` was calling `URI.create(rawUri)`
on an origin-form `rawUri` (RFC 9112 §3.2.1, the normal form in HTTP/1.1), e.g.
`/foo?bar=1` — which produces a URI with `authority=null`, `scheme=null`,
`host=null`.

**Fix**: `HttpRequestImpl.uri()` now reconstructs an absolute URI
`scheme://host+rawUri` by reading the `Host` header. If `rawUri` is already
absolute-form (proxy), it is used as-is. If `Host` is absent, falls back to
`URI.create(rawUri)` (historical behaviour).

- Source: `chappe-http/src/main/java/fr/vidocq/chappe/http/HttpRequestImpl.java`
  — method `buildUri()`.
- Regression test: `ExtensionSpiTest#requestUriHasAuthorityFromHostHeader`.
- 65/65 Chappe tests pass after the fix.

**Action on Cassini side**: the `Host` fallback in `Invoker` remains in place
(backward compat with older Chappe), but will become redundant after the bump.

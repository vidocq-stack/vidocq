<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<fmt:setLocale value="fr"/>
<fmt:setBundle basename="io.vidocq.bodoni.it.ibarra.i18n.Labels" var="labels"/>
<html><body>
<p id="greeting"><fmt:message key="greeting" bundle="${labels}"/></p>
<p id="plain"><fmt:bundle basename="it-bundles.Plain"><fmt:message key="hello"><fmt:param value="Bodoni"/></fmt:message></fmt:bundle></p>
<p id="number"><fmt:formatNumber value="345987.246"/></p>
<fmt:parseDate value="4/13/02" pattern="M/d/yy" var="d" timeZone="GMT"/>
<p id="date"><fmt:formatDate value="${d}" pattern="dd.MM.yy" timeZone="GMT"/></p>
<p id="full"><fmt:timeZone value="GMT"><fmt:formatDate value="${d}" dateStyle="full"/></fmt:timeZone></p>
</body></html>

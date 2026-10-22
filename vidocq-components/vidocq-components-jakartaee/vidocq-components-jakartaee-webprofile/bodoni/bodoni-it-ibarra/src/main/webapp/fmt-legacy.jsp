<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<fmt:setLocale value="fr"/>
<fmt:setBundle basename="io.vidocq.bodoni.it.ibarra.i18n.Labels" var="labels"/>
<html><body>
<p id="message"><fmt:message key="greeting" bundle="${labels}"/></p>
<p id="number"><fmt:formatNumber value="345987.246"/></p>
</body></html>

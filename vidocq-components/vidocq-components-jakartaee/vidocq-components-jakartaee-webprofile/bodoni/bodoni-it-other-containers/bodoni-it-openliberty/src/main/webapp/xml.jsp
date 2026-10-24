<%@ page contentType="text/plain" %><%@ taglib prefix="x" uri="jakarta.tags.xml" %><%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ taglib prefix="probe" uri="urn:bodoni:it:probe" %><x:parse var="doc"><people><p>ada</p><p>grace</p></people></x:parse>
<x:forEach select="$doc/people/p" varStatus="s"><probe:parent/></x:forEach>
NAMES=<x:forEach select="$doc/people/p" varStatus="s"><x:out select="."/><c:if test="${!s.last}">,</c:if></x:forEach>

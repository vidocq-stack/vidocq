<%@ page contentType="text/html; charset=UTF-8" %>
<%-- External references of x:parse under the same-origin policy (Tags 3.0 §11.1.7, Bodoni PLAN.md
     open question 6). The client passes base (an absolute system id on this server) and cross (the
     same server under another host name): no EL property access is needed. --%>
<%@ taglib prefix="x" uri="jakarta.tags.xml" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<x:parse var="relative" systemId="/xml/catalog.xml"><!DOCTYPE catalog [<!ENTITY part SYSTEM "parts/part.xml">]><catalog>&part;</catalog></x:parse>
<x:parse var="absolute" systemId="${param.base}"><!DOCTYPE catalog [<!ENTITY part SYSTEM "xml-part.jsp">]><catalog>&part;</catalog></x:parse>
<c:catch var="cross"><x:parse var="crossDoc" systemId="${param.base}"><!DOCTYPE catalog [<!ENTITY part SYSTEM "${param.cross}">]><catalog>&part;</catalog></x:parse></c:catch>
<c:catch var="redirect"><x:parse var="redirectDoc" systemId="${param.base}"><!DOCTYPE catalog [<!ENTITY part SYSTEM "redirect.jsp">]><catalog>&part;</catalog></x:parse></c:catch>
<c:catch var="none"><x:parse var="noneDoc"><!DOCTYPE catalog [<!ENTITY part SYSTEM "parts/part.xml">]><catalog>&part;</catalog></x:parse></c:catch>
<html><body>
<p id="relative"><x:out select="$relative//name"/></p>
<p id="absolute"><x:out select="$absolute//name"/></p>
<p id="cross">${cross}</p>
<p id="redirect">${redirect}</p>
<p id="none">${none}</p>
</body></html>

<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="x" uri="http://java.sun.com/jsp/jstl/xml" %>
<x:parse var="doc"><press><city>Parma</city></press></x:parse>
<html><body>
<p id="city"><x:out select="$doc/press/city"/></p>
</body></html>

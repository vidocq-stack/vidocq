<%@ page pageEncoding="UTF-8" contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="bistro" uri="https://vidocq.io/examples/bistro" %>
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>About · Vidocq Bistro</title></head>
<body>
<h1>About the Bistro</h1>
<p>
Welcome, visitor from <bistro:out value="${empty param.from ? 'nowhere' : param.from}" escapeXml="true"/>.</p>
<p>This page is a JSP compiled by Ibarra at build time; Foy serves it, Peano evaluates its expressions.
Tonight's special: beef bourguignon, <bistro:out value="${bistro:euros(1850)}" escapeXml="true"/>.</p>
<p><a href="./">Back to the menu</a></p>
</body></html>

<%@ page pageEncoding="UTF-8" contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="bistro" uri="https://vidocq.io/examples/bistro" %>
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>About · Vidocq Bistro</title></head>
<body>
<h1>About the Bistro</h1>
<p>Welcome, visitor from ${empty param.from ? 'nowhere' : bistro:escape(param.from)}.</p>
<p>This page is a JSP compiled by Ibarra at build time; Foy serves it, Peano evaluates its expressions.
Tonight's special: beef bourguignon, ${bistro:euros(1850)}.</p>
<p><a href="${pageContext.request.contextPath}/">Back to the menu</a></p>
</body></html>

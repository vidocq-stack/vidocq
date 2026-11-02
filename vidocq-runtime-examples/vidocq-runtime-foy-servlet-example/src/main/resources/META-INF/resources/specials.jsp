<%@ page pageEncoding="UTF-8" contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="bistro" uri="https://vidocq.io/examples/bistro" %>
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Specials · Vidocq Bistro</title></head>
<body>
<h1>Tonight's special</h1>
<p>${specials.dish}, ${bistro:euros(specials.priceCents)}.</p>
</body></html>

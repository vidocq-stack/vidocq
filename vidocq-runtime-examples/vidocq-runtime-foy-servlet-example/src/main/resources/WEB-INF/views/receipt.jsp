<%@ page pageEncoding="UTF-8" contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="bistro" uri="https://vidocq.io/examples/bistro" %>
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Receipt · Vidocq Bistro</title></head>
<body>
<h1>Receipt</h1>
<ul>
<bistro:each items="${receipt.lines}" var="line">  <li>${line.quantity} × ${line.dish} — ${line.amount}</li>
</bistro:each></ul>
<p>Total ${receipt.total}</p>
<p><a href="${pageContext.request.contextPath}/basket">Back to the basket</a></p>
</body></html>

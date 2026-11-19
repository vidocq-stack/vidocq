<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<html><body>
<p id="join">${fn:join(fn:split('a,b,,c', ','), '-')}</p>
<p id="upper">${fn:toUpperCase('bodoni')}</p>
<p id="lower">${fn:toLowerCase('PARMA')}</p>
<p id="contains">${fn:contains('typography', 'graph')}/${fn:containsIgnoreCase('Manuale', 'MAN')}</p>
<p id="affixes">${fn:startsWith('Bodoni', 'Bod')}/${fn:endsWith('Bodoni', 'ni')}</p>
<p id="index">${fn:indexOf('Bodoni', 'don')}</p>
<p id="substring">${fn:substring('Manuale Tipografico', 0, 7)}|${fn:substringAfter('a=b', '=')}|${fn:substringBefore('a=b', '=')}</p>
<p id="replace">${fn:replace('1788', '7', '8')}</p>
<p id="trim">[${fn:trim('  Parma  ')}]</p>
<p id="escape">${fn:escapeXml('<i>&</i>')}</p>
<p id="length">${fn:length('Bodoni')}/${fn:length(null)}</p>
</body></html>

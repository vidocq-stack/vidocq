window.onload = function() {
  // Vidocq: point Swagger UI at the Grimm-served OpenAPI document (same origin as this UI,
  // so no CORS is involved). The document is requested in JSON via the ?format= override
  // (MicroProfile OpenAPI 4.1 §2.3); Grimm otherwise serves YAML by default (§2.2).
  window.ui = SwaggerUIBundle({
    url: "/openapi?format=json",
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [
      SwaggerUIBundle.presets.apis,
      SwaggerUIStandalonePreset
    ],
    plugins: [
      SwaggerUIBundle.plugins.DownloadUrl
    ],
    layout: "StandaloneLayout"
  });
};

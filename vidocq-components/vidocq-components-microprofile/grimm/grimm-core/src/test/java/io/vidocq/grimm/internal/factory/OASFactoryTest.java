package io.vidocq.grimm.internal.factory;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.*;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.info.Contact;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.info.License;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.*;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.OAuthFlow;
import org.eclipse.microprofile.openapi.models.security.OAuthFlows;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — M1: OASFactory + model POJOs round-trip.
 * Spec §3.1 (Model), §3.2 (OASFactory).
 */
class OASFactoryTest {

    // ── §3.2 OASFactory can create all Constructible types ──────────────────

    @Test
    void oasFactory_createsOpenAPI() {
        OpenAPI api = OASFactory.createObject(OpenAPI.class);
        assertNotNull(api);
        api.setOpenapi("3.1.0");
        assertEquals("3.1.0", api.getOpenapi());
    }

    @Test
    void oasFactory_createsInfo() {
        Info info = OASFactory.createObject(Info.class);
        assertNotNull(info);
        info.setTitle("My API");
        info.setVersion("1.0.0");
        assertEquals("My API", info.getTitle());
        assertEquals("1.0.0", info.getVersion());
    }

    @Test
    void oasFactory_createsContact() {
        Contact c = OASFactory.createObject(Contact.class);
        assertNotNull(c);
        c.setName("Alice");
        c.setEmail("alice@example.com");
        assertEquals("Alice", c.getName());
        assertEquals("alice@example.com", c.getEmail());
    }

    @Test
    void oasFactory_createsLicense() {
        License l = OASFactory.createObject(License.class);
        assertNotNull(l);
        l.setName("Apache-2.0");
        l.setUrl("https://www.apache.org/licenses/LICENSE-2.0");
        assertEquals("Apache-2.0", l.getName());
    }

    @Test
    void oasFactory_createsTag() {
        Tag t = OASFactory.createObject(Tag.class);
        assertNotNull(t);
        t.setName("pets");
        assertEquals("pets", t.getName());
    }

    @Test
    void oasFactory_createsServer() {
        Server s = OASFactory.createObject(Server.class);
        assertNotNull(s);
        s.setUrl("https://api.example.com");
        assertEquals("https://api.example.com", s.getUrl());
    }

    @Test
    void oasFactory_createsServerVariable() {
        ServerVariable sv = OASFactory.createObject(ServerVariable.class);
        assertNotNull(sv);
        sv.setDefaultValue("8080");
        assertEquals("8080", sv.getDefaultValue());
    }

    @Test
    void oasFactory_createsExternalDocumentation() {
        ExternalDocumentation ed = OASFactory.createObject(ExternalDocumentation.class);
        assertNotNull(ed);
        ed.setUrl("https://docs.example.com");
        assertEquals("https://docs.example.com", ed.getUrl());
    }

    @Test
    void oasFactory_createsPaths() {
        Paths p = OASFactory.createObject(Paths.class);
        assertNotNull(p);
    }

    @Test
    void oasFactory_createsPathItem() {
        PathItem pi = OASFactory.createObject(PathItem.class);
        assertNotNull(pi);
        pi.setSummary("Pet operations");
        assertEquals("Pet operations", pi.getSummary());
    }

    @Test
    void oasFactory_createsOperation() {
        Operation op = OASFactory.createObject(Operation.class);
        assertNotNull(op);
        op.setOperationId("listPets");
        assertEquals("listPets", op.getOperationId());
    }

    @Test
    void oasFactory_createsParameter() {
        Parameter p = OASFactory.createObject(Parameter.class);
        assertNotNull(p);
        p.setName("limit");
        p.setIn(Parameter.In.QUERY);
        assertEquals("limit", p.getName());
        assertEquals(Parameter.In.QUERY, p.getIn());
    }

    @Test
    void oasFactory_createsRequestBody() {
        RequestBody rb = OASFactory.createObject(RequestBody.class);
        assertNotNull(rb);
        rb.setRequired(true);
        assertTrue(rb.getRequired());
    }

    @Test
    void oasFactory_createsAPIResponse() {
        APIResponse r = OASFactory.createObject(APIResponse.class);
        assertNotNull(r);
        r.setDescription("OK");
        assertEquals("OK", r.getDescription());
    }

    @Test
    void oasFactory_createsAPIResponses() {
        APIResponses rs = OASFactory.createObject(APIResponses.class);
        assertNotNull(rs);
    }

    @Test
    void oasFactory_createsSchema() {
        Schema s = OASFactory.createObject(Schema.class);
        assertNotNull(s);
        s.setTitle("Pet");
        s.setDescription("A pet object");
        assertEquals("Pet", s.getTitle());
        assertEquals("A pet object", s.getDescription());
    }

    @Test
    void oasFactory_createsContent() {
        Content c = OASFactory.createObject(Content.class);
        assertNotNull(c);
    }

    @Test
    void oasFactory_createsMediaType() {
        MediaType mt = OASFactory.createObject(MediaType.class);
        assertNotNull(mt);
    }

    @Test
    void oasFactory_createsEncoding() {
        Encoding e = OASFactory.createObject(Encoding.class);
        assertNotNull(e);
        e.setContentType("application/json");
        assertEquals("application/json", e.getContentType());
    }

    @Test
    void oasFactory_createsDiscriminator() {
        Discriminator d = OASFactory.createObject(Discriminator.class);
        assertNotNull(d);
        d.setPropertyName("petType");
        assertEquals("petType", d.getPropertyName());
    }

    @Test
    void oasFactory_createsXML() {
        XML x = OASFactory.createObject(XML.class);
        assertNotNull(x);
        x.setName("Pet");
        assertEquals("Pet", x.getName());
    }

    @Test
    void oasFactory_createsCallback() {
        Callback cb = OASFactory.createObject(Callback.class);
        assertNotNull(cb);
    }

    @Test
    void oasFactory_createsLink() {
        Link l = OASFactory.createObject(Link.class);
        assertNotNull(l);
        l.setOperationId("getPetById");
        assertEquals("getPetById", l.getOperationId());
    }

    @Test
    void oasFactory_createsHeader() {
        Header h = OASFactory.createObject(Header.class);
        assertNotNull(h);
        h.setDescription("X-Rate-Limit");
        assertEquals("X-Rate-Limit", h.getDescription());
    }

    @Test
    void oasFactory_createsExample() {
        Example e = OASFactory.createObject(Example.class);
        assertNotNull(e);
        e.setSummary("A sample example");
        assertEquals("A sample example", e.getSummary());
    }

    @Test
    void oasFactory_createsSecurityScheme() {
        SecurityScheme ss = OASFactory.createObject(SecurityScheme.class);
        assertNotNull(ss);
        ss.setType(SecurityScheme.Type.HTTP);
        assertEquals(SecurityScheme.Type.HTTP, ss.getType());
    }

    @Test
    void oasFactory_createsSecurityRequirement() {
        SecurityRequirement sr = OASFactory.createObject(SecurityRequirement.class);
        assertNotNull(sr);
        sr.addScheme("bearerAuth");
        assertNotNull(sr.getSchemes());
        assertTrue(sr.getSchemes().containsKey("bearerAuth"));
    }

    @Test
    void oasFactory_createsOAuthFlows() {
        OAuthFlows flows = OASFactory.createObject(OAuthFlows.class);
        assertNotNull(flows);
    }

    @Test
    void oasFactory_createsOAuthFlow() {
        OAuthFlow flow = OASFactory.createObject(OAuthFlow.class);
        assertNotNull(flow);
        flow.setAuthorizationUrl("https://auth.example.com/oauth/authorize");
        assertEquals("https://auth.example.com/oauth/authorize", flow.getAuthorizationUrl());
    }

    @Test
    void oasFactory_createsComponents() {
        Components c = OASFactory.createObject(Components.class);
        assertNotNull(c);
    }

    // ── §3.1 builder-style fluent API round-trip ──────────────────────────

    @Test
    void openAPI_builderRoundTrip() {
        // §3.1 full round-trip via builder API
        OpenAPI api = OASFactory.createObject(OpenAPI.class)
                .openapi("3.1.0")
                .info(OASFactory.createObject(Info.class)
                        .title("Pet Store")
                        .version("1.0.0"))
                .paths(OASFactory.createObject(Paths.class)
                        .addPathItem("/pets", OASFactory.createObject(PathItem.class)
                                .GET(OASFactory.createObject(Operation.class)
                                        .operationId("listPets")
                                        .responses(OASFactory.createObject(APIResponses.class)
                                                .addAPIResponse("200", OASFactory.createObject(APIResponse.class)
                                                        .description("A list of pets"))))));

        assertEquals("3.1.0", api.getOpenapi());
        assertEquals("Pet Store", api.getInfo().getTitle());
        assertNotNull(api.getPaths().getPathItem("/pets"));
        assertEquals("listPets",
                api.getPaths().getPathItem("/pets").getGET().getOperationId());
    }

    @Test
    void extensions_addAndRetrieve() {
        // §4.2 extensions prefixed with "x-"
        Info info = OASFactory.createObject(Info.class);
        info.addExtension("x-internal-id", 42);
        assertEquals(42, info.getExtension("x-internal-id"));
        assertTrue(info.hasExtension("x-internal-id"));

        info.removeExtension("x-internal-id");
        assertFalse(info.hasExtension("x-internal-id"));
    }

    @Test
    void schema_addProperty() {
        Schema schema = OASFactory.createObject(Schema.class);
        Schema nameSchema = OASFactory.createObject(Schema.class).title("name");
        schema.addProperty("name", nameSchema);
        assertNotNull(schema.getProperties());
        assertEquals(nameSchema, schema.getProperties().get("name"));
    }

    @Test
    void paths_addAndContains() {
        Paths paths = OASFactory.createObject(Paths.class);
        PathItem item = OASFactory.createObject(PathItem.class);
        paths.addPathItem("/users", item);
        assertTrue(paths.hasPathItem("/users"));
        assertEquals(item, paths.getPathItem("/users"));
        paths.removePathItem("/users");
        assertFalse(paths.hasPathItem("/users"));
    }
}


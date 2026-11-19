/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.grimm.internal.factory;

import io.vidocq.grimm.internal.model.APIResponseImpl;
import io.vidocq.grimm.internal.model.APIResponsesImpl;
import io.vidocq.grimm.internal.model.CallbackImpl;
import io.vidocq.grimm.internal.model.ComponentsImpl;
import io.vidocq.grimm.internal.model.ContactImpl;
import io.vidocq.grimm.internal.model.ContentImpl;
import io.vidocq.grimm.internal.model.DiscriminatorImpl;
import io.vidocq.grimm.internal.model.EncodingImpl;
import io.vidocq.grimm.internal.model.ExampleImpl;
import io.vidocq.grimm.internal.model.ExternalDocumentationImpl;
import io.vidocq.grimm.internal.model.HeaderImpl;
import io.vidocq.grimm.internal.model.InfoImpl;
import io.vidocq.grimm.internal.model.LicenseImpl;
import io.vidocq.grimm.internal.model.LinkImpl;
import io.vidocq.grimm.internal.model.MediaTypeImpl;
import io.vidocq.grimm.internal.model.OAuthFlowImpl;
import io.vidocq.grimm.internal.model.OAuthFlowsImpl;
import io.vidocq.grimm.internal.model.OpenAPIImpl;
import io.vidocq.grimm.internal.model.OperationImpl;
import io.vidocq.grimm.internal.model.ParameterImpl;
import io.vidocq.grimm.internal.model.PathItemImpl;
import io.vidocq.grimm.internal.model.PathsImpl;
import io.vidocq.grimm.internal.model.RequestBodyImpl;
import io.vidocq.grimm.internal.model.SchemaImpl;
import io.vidocq.grimm.internal.model.SecurityRequirementImpl;
import io.vidocq.grimm.internal.model.SecuritySchemeImpl;
import io.vidocq.grimm.internal.model.ServerImpl;
import io.vidocq.grimm.internal.model.ServerVariableImpl;
import io.vidocq.grimm.internal.model.TagImpl;
import io.vidocq.grimm.internal.model.XMLImpl;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.Constructible;
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.info.Contact;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.info.License;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Discriminator;
import org.eclipse.microprofile.openapi.models.media.Encoding;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.XML;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class OASFactoryImpl {

    private static final Map<Class<? extends Constructible>, Supplier<? extends Constructible>> FACTORIES =
            new LinkedHashMap<>();

    static {
        FACTORIES.put(OpenAPI.class, OpenAPIImpl::new);
        FACTORIES.put(Info.class, InfoImpl::new);
        FACTORIES.put(Contact.class, ContactImpl::new);
        FACTORIES.put(License.class, LicenseImpl::new);
        FACTORIES.put(Tag.class, TagImpl::new);
        FACTORIES.put(Server.class, ServerImpl::new);
        FACTORIES.put(ServerVariable.class, ServerVariableImpl::new);
        FACTORIES.put(ExternalDocumentation.class, ExternalDocumentationImpl::new);
        FACTORIES.put(Paths.class, PathsImpl::new);
        FACTORIES.put(PathItem.class, PathItemImpl::new);
        FACTORIES.put(Operation.class, OperationImpl::new);
        FACTORIES.put(Parameter.class, ParameterImpl::new);
        FACTORIES.put(RequestBody.class, RequestBodyImpl::new);
        FACTORIES.put(APIResponse.class, APIResponseImpl::new);
        FACTORIES.put(APIResponses.class, APIResponsesImpl::new);
        FACTORIES.put(Schema.class, SchemaImpl::new);
        FACTORIES.put(Content.class, ContentImpl::new);
        FACTORIES.put(MediaType.class, MediaTypeImpl::new);
        FACTORIES.put(Encoding.class, EncodingImpl::new);
        FACTORIES.put(Discriminator.class, DiscriminatorImpl::new);
        FACTORIES.put(XML.class, XMLImpl::new);
        FACTORIES.put(Callback.class, CallbackImpl::new);
        FACTORIES.put(Link.class, LinkImpl::new);
        FACTORIES.put(Header.class, HeaderImpl::new);
        FACTORIES.put(Example.class, ExampleImpl::new);
        FACTORIES.put(SecurityScheme.class, SecuritySchemeImpl::new);
        FACTORIES.put(SecurityRequirement.class, SecurityRequirementImpl::new);
        FACTORIES.put(OAuthFlows.class, OAuthFlowsImpl::new);
        FACTORIES.put(OAuthFlow.class, OAuthFlowImpl::new);
        FACTORIES.put(Components.class, ComponentsImpl::new);
    }

    private OASFactoryImpl() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends Constructible> T createObject(Class<T> clazz) {
        if (clazz == null) {
            throw new NullPointerException("clazz must not be null");
        }

        Supplier<? extends Constructible> supplier = FACTORIES.get(clazz);
        if (supplier == null) {
            throw new IllegalArgumentException("Unsupported OpenAPI constructible type: " + clazz.getName());
        }

        return (T) supplier.get();
    }
}


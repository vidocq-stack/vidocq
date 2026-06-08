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
package io.vidocq.runtime.examples.mansart;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only DB inspector — bypasses Mansart Data and runs a raw {@code SELECT *} via JDBC.
 * Used by the demo UI side-by-side with {@link ProductResource} so a developer can <i>see</i>
 * that Mansart's repository writes really hit the same H2 table the inspector reads from.
 */
@ApplicationScoped
@Path("/db")
@Produces(MediaType.APPLICATION_JSON)
public class DatabaseInspectorResource {

    @Inject
    Instance<DataSource> dataSourceInstance;

    @GET
    @Path("/products")
    public List<Map<String, Object>> rawProducts() {
        DataSource ds = dataSourceInstance.get();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT \"id\", \"name\", \"price\" FROM \"products\" ORDER BY \"id\"")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("price", rs.getDouble("price"));
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Raw SELECT failed", e);
        }
        return rows;
    }
}

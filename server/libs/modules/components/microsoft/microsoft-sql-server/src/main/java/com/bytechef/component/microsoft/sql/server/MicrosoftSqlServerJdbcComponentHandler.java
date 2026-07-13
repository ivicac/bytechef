/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.component.microsoft.sql.server;

import static com.bytechef.platform.component.definition.JdbcComponentDsl.jdbcComponent;

import com.bytechef.platform.component.JdbcComponentHandler;
import com.bytechef.platform.component.definition.JdbcComponentDefinition;
import com.google.auto.service.AutoService;

/**
 * @author Ivica Cardic
 */
@AutoService(JdbcComponentHandler.class)
public class MicrosoftSqlServerJdbcComponentHandler implements JdbcComponentHandler {

    private static final JdbcComponentDefinition COMPONENT_DEFINITION = jdbcComponent("microsoftSqlServer")
        .title("Microsoft SQL Server")
        .description("Query, insert and update data from Microsoft SQL Server.")
        .icon("path:assets/microsoft-sql-server.svg")
        .urlTemplate("jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=true;trustServerCertificate=true")
        .jdbcDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

    @Override
    public JdbcComponentDefinition getJdbcComponentDefinition() {
        return COMPONENT_DEFINITION;
    }
}

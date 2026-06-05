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

package com.bytechef.component.ai.agent.chat.memory.session.util;

import java.sql.DatabaseMetaData;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * @author Ivica Cardic
 */
public class SessionChatMemoryUtils {

    private SessionChatMemoryUtils() {
    }

    public static void initializeSchema(DataSource dataSource) {
        String schemaScript = resolveSchemaScript(dataSource);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(schemaScript));

        populator.setContinueOnError(true);

        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    private static String resolveSchemaScript(DataSource dataSource) {
        String productName = null;

        try {
            productName = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
        } catch (Exception ignored) {
        }

        String schemaName = switch (productName != null ? productName : "") {
            case "MySQL", "MariaDB" -> "schema-mysql.sql";
            case "H2" -> "schema-h2.sql";
            default -> "schema-postgresql.sql";
        };

        return "org/springframework/ai/session/jdbc/" + schemaName;
    }
}

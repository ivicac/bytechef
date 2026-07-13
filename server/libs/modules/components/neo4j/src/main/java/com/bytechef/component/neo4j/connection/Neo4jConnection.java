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

package com.bytechef.component.neo4j.connection;

import static com.bytechef.component.definition.Authorization.PASSWORD;
import static com.bytechef.component.definition.Authorization.USERNAME;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.neo4j.constant.Neo4jConstants.URL;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class Neo4jConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url;
        })
        .authorizations(
            authorization(AuthorizationType.BASIC_AUTH)
                .title("Basic Authentication")
                .properties(
                    string(URL)
                        .label("URL")
                        .description(
                            "The HTTP URL of your Neo4j server (e.g. https://myinstance.databases.neo4j.io).")
                        .required(true),
                    string(USERNAME)
                        .label("Username")
                        .description("The username of the Neo4j user.")
                        .required(true),
                    string(PASSWORD)
                        .label("Password")
                        .description("The password of the Neo4j user.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/neo4j_v1#connection-setup");

    private Neo4jConnection() {
    }
}

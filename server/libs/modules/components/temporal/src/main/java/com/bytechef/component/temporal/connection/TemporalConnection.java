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

package com.bytechef.component.temporal.connection;

import static com.bytechef.component.definition.Authorization.KEY;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class TemporalConnection {

    public static final String SERVER_URL = "serverUrl";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getRequiredString(SERVER_URL) + "/api/v1")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(SERVER_URL)
                        .label("Server URL")
                        .description("The Temporal HTTP API server URL, e.g. http://localhost:7243.")
                        .required(true),
                    string(KEY)
                        .label("API Key")
                        .description("The Temporal Cloud API key. Leave empty for servers without auth.")
                        .required(false))
                .apply((connectionParameters, context) -> {
                    String key = connectionParameters.getString(KEY);

                    if (key == null || key.isEmpty()) {
                        return ApplyResponse.ofHeaders(Map.of());
                    }

                    return ApplyResponse.ofHeaders(Map.of("Authorization", List.of("Bearer " + key)));
                }));

    private TemporalConnection() {
    }
}

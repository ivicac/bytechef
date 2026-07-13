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

package com.bytechef.component.dagster.connection;

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
public class DagsterConnection {

    public static final String HOST = "host";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getRequiredString(HOST))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Token")
                .properties(
                    string(HOST)
                        .label("Host")
                        .description(
                            "The Dagster GraphQL host, e.g. https://myorg.dagster.cloud/prod for Dagster+ or " +
                                "http://localhost:3000 for open source.")
                        .required(true),
                    string(KEY)
                        .label("API Token")
                        .description("The Dagster+ user token. Leave empty for open-source deployments without auth.")
                        .required(false))
                .apply((connectionParameters, context) -> {
                    String token = connectionParameters.getString(KEY);

                    if (token == null || token.isEmpty()) {
                        return ApplyResponse.ofHeaders(Map.of());
                    }

                    return ApplyResponse.ofHeaders(Map.of("Dagster-Cloud-Api-Token", List.of(token)));
                }));

    private DagsterConnection() {
    }
}

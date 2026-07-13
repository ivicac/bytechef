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

package com.bytechef.component.algolia.connection;

import static com.bytechef.component.algolia.constant.AlgoliaConstants.APPLICATION_ID;
import static com.bytechef.component.definition.Authorization.API_TOKEN;
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
public class AlgoliaConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://%s-dsn.algolia.net/1"
            .formatted(connectionParameters.getRequiredString(APPLICATION_ID)))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(APPLICATION_ID)
                        .label("Application ID")
                        .description("The id of your Algolia application.")
                        .required(true),
                    string(API_TOKEN)
                        .label("API Key")
                        .description("The API key found in your Algolia dashboard.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "X-Algolia-Application-Id", List.of(connectionParameters.getString(APPLICATION_ID)),
                        "X-Algolia-API-Key", List.of(connectionParameters.getString(API_TOKEN))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/algolia_v1#connection-setup");

    private AlgoliaConnection() {
    }
}

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

package com.bytechef.component.ghost.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.ghost.constant.GhostConstants.API_URL;
import static com.bytechef.component.ghost.constant.GhostConstants.CONTENT_API_KEY;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class GhostConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String apiUrl = connectionParameters.getRequiredString(API_URL);

            if (apiUrl.endsWith("/")) {
                apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
            }

            return apiUrl + "/ghost/api/content";
        })
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("Content API Key")
                .properties(
                    string(API_URL)
                        .label("API URL")
                        .description("The URL of your Ghost site (e.g. https://demo.ghost.io).")
                        .required(true),
                    string(CONTENT_API_KEY)
                        .label("Content API Key")
                        .description(
                            "The Content API key of a custom integration created in Ghost Admin → Settings → " +
                                "Integrations.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofQueryParameters(
                    Map.of("key", List.of(connectionParameters.getRequiredString(CONTENT_API_KEY))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/ghost_v1#connection-setup");

    private GhostConnection() {
    }
}

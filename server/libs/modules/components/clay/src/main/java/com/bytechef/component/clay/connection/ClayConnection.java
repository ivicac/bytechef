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

package com.bytechef.component.clay.connection;

import static com.bytechef.component.clay.constant.ClayConstants.AUTH_TOKEN;
import static com.bytechef.component.clay.constant.ClayConstants.WEBHOOK_URL;
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
public class ClayConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getRequiredString(WEBHOOK_URL))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(WEBHOOK_URL)
                        .label("Webhook URL")
                        .description("The Clay table's webhook URL.")
                        .required(true),
                    string(AUTH_TOKEN)
                        .label("Auth Token")
                        .description("Optional webhook auth token.")
                        .required(false))
                .apply((connectionParameters, context) -> {
                    String authToken = connectionParameters.getString(AUTH_TOKEN);

                    if (authToken != null && !authToken.isBlank()) {
                        return ApplyResponse.ofHeaders(Map.of("x-clay-webhook-auth", List.of(authToken)));
                    }

                    return ApplyResponse.ofHeaders(Map.of());
                }));

    private ClayConnection() {
    }
}

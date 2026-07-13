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

package com.bytechef.component.campaign.monitor.connection;

import static com.bytechef.component.definition.Authorization.KEY;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class CampaignMonitorConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.createsend.com/api/v3.3")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(KEY)
                        .label("API Key")
                        .description("The API key found in your Campaign Monitor account settings.")
                        .required(true))
                .apply((connectionParameters, context) -> {
                    Base64.Encoder encoder = Base64.getEncoder();

                    String credentials = encoder.encodeToString(
                        (connectionParameters.getString(KEY) + ":x").getBytes(StandardCharsets.UTF_8));

                    return ApplyResponse.ofHeaders(Map.of("Authorization", List.of("Basic " + credentials)));
                }));

    private CampaignMonitorConnection() {
    }
}

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

package com.bytechef.component.highlevel.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
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
public class HighLevelConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://services.leadconnectorhq.com")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("Private Integration Token")
                .properties(
                    string(TOKEN)
                        .label("Private Integration Token")
                        .description(
                            "The token of a private integration created in HighLevel under Settings → Private " +
                                "Integrations.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "Authorization", List.of("Bearer " + connectionParameters.getRequiredString(TOKEN)),
                        "Version", List.of("2021-07-28")))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/highLevel_v1#connection-setup");

    private HighLevelConnection() {
    }
}

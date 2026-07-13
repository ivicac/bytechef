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

package com.bytechef.component.jungle.scout.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.jungle.scout.constant.JungleScoutConstants.API_KEY;
import static com.bytechef.component.jungle.scout.constant.JungleScoutConstants.KEY_NAME;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class JungleScoutConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://developer.junglescout.com/api")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(KEY_NAME)
                        .label("Key Name")
                        .description("The name of the API key created in the Jungle Scout developer settings.")
                        .required(true),
                    string(API_KEY)
                        .label("API Key")
                        .description("The API key created in the Jungle Scout developer settings.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "Authorization",
                        List.of(
                            connectionParameters.getRequiredString(KEY_NAME) + ":" +
                                connectionParameters.getRequiredString(API_KEY)),
                        "X-API-Type", List.of("junglescout"),
                        "Accept", List.of("application/vnd.junglescout.v1+json")))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/jungleScout_v1#connection-setup");

    private JungleScoutConnection() {
    }
}

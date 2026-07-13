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

package com.bytechef.component.wealthbox.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.wealthbox.constant.WealthboxConstants.ACCESS_TOKEN;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class WealthboxConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.crmworkspace.com/v1")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("Access Token")
                .properties(
                    string(ACCESS_TOKEN)
                        .label("Access Token")
                        .description("The API access token created in your Wealthbox account settings.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of("ACCESS_TOKEN", List.of(connectionParameters.getString(ACCESS_TOKEN))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/wealthbox_v1#connection-setup");

    private WealthboxConnection() {
    }
}

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

package com.bytechef.component.quickbase.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.quickbase.constant.QuickBaseConstants.REALM_HOSTNAME;
import static com.bytechef.component.quickbase.constant.QuickBaseConstants.USER_TOKEN;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class QuickBaseConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.quickbase.com/v1")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("User Token")
                .properties(
                    string(REALM_HOSTNAME)
                        .label("Realm Hostname")
                        .description("The hostname of your Quickbase realm, e.g. demo.quickbase.com.")
                        .required(true),
                    string(USER_TOKEN)
                        .label("User Token")
                        .description("The user token created in your Quickbase account settings.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "QB-Realm-Hostname", List.of(connectionParameters.getString(REALM_HOSTNAME)),
                        "Authorization",
                        List.of("QB-USER-TOKEN " + connectionParameters.getString(USER_TOKEN))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/quickbase_v1#connection-setup");

    private QuickBaseConnection() {
    }
}

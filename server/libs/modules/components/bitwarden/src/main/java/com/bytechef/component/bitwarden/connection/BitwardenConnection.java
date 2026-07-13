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

package com.bytechef.component.bitwarden.connection;

import static com.bytechef.component.definition.Authorization.CLIENT_ID;
import static com.bytechef.component.definition.Authorization.CLIENT_SECRET;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BitwardenConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.bitwarden.com/public")
        .authorizations(
            authorization(AuthorizationType.OAUTH2_CLIENT_CREDENTIALS)
                .title("OAuth2 Client Credentials")
                .properties(
                    string(CLIENT_ID)
                        .label("Client Id")
                        .description("The organization client id (organization.<uuid>).")
                        .required(true),
                    string(CLIENT_SECRET)
                        .label("Client Secret")
                        .description("The organization client secret from the Bitwarden admin console.")
                        .required(true))
                .tokenUrl((connectionParameters, context) -> "https://identity.bitwarden.com/connect/token")
                .scopes((connectionParameters, context) -> Map.of("api.organization", true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/bitwarden_v1#connection-setup");

    private BitwardenConnection() {
    }
}

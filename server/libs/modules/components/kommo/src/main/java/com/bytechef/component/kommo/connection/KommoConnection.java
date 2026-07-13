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

package com.bytechef.component.kommo.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.kommo.constant.KommoConstants.SUBDOMAIN;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class KommoConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://%s.kommo.com/api/v4"
            .formatted(connectionParameters.getRequiredString(SUBDOMAIN)))
        .authorizations(
            authorization(AuthorizationType.BEARER_TOKEN)
                .title("Long-Lived Token")
                .properties(
                    string(SUBDOMAIN)
                        .label("Subdomain")
                        .description("The subdomain of your Kommo account (https://SUBDOMAIN.kommo.com).")
                        .required(true),
                    string(TOKEN)
                        .label("Long-Lived Token")
                        .description("The long-lived token created for a private integration in Kommo.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/kommo_v1#connection-setup");

    private KommoConnection() {
    }
}

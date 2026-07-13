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

package com.bytechef.component.gorgias.connection;

import static com.bytechef.component.definition.Authorization.PASSWORD;
import static com.bytechef.component.definition.Authorization.USERNAME;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class GorgiasConnection {

    public static final String DOMAIN = "domain";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://%s.gorgias.com/api".formatted(
            connectionParameters.getRequiredString(DOMAIN)))
        .authorizations(
            authorization(AuthorizationType.BASIC_AUTH)
                .title("Basic Auth")
                .properties(
                    string(DOMAIN)
                        .label("Domain")
                        .description("The name of your Gorgias domain (https://DOMAIN.gorgias.com).")
                        .required(true),
                    string(USERNAME)
                        .label("Email")
                        .description("The email address of your Gorgias user.")
                        .required(true),
                    string(PASSWORD)
                        .label("API Key")
                        .description("The API key created in Gorgias under Settings -> REST API.")
                        .required(true)));

    private GorgiasConnection() {
    }
}

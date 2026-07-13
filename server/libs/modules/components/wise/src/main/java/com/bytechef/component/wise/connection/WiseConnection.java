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

package com.bytechef.component.wise.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class WiseConnection {

    public static final String ENVIRONMENT = "environment";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getString(
            ENVIRONMENT, "https://api.transferwise.com"))
        .authorizations(
            authorization(AuthorizationType.BEARER_TOKEN)
                .title("API Token")
                .properties(
                    string(ENVIRONMENT)
                        .label("Environment")
                        .description("The Wise environment to connect to.")
                        .options(
                            option("Live", "https://api.transferwise.com"),
                            option("Sandbox", "https://api.sandbox.transferwise.tech"))
                        .defaultValue("https://api.transferwise.com")
                        .required(true),
                    string(TOKEN)
                        .label("API Token")
                        .description("The API token created in your Wise account settings.")
                        .required(true)));

    private WiseConnection() {
    }
}

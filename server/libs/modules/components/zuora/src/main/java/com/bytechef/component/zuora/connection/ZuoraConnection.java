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

package com.bytechef.component.zuora.connection;

import static com.bytechef.component.definition.Authorization.CLIENT_ID;
import static com.bytechef.component.definition.Authorization.CLIENT_SECRET;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class ZuoraConnection {

    public static final String ENVIRONMENT = "environment";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getString(
            ENVIRONMENT, "https://rest.zuora.com"))
        .authorizations(
            authorization(AuthorizationType.OAUTH2_CLIENT_CREDENTIALS)
                .title("OAuth2 Client Credentials")
                .properties(
                    string(ENVIRONMENT)
                        .label("Environment")
                        .description("The Zuora environment to connect to.")
                        .options(
                            option("Production", "https://rest.zuora.com"),
                            option("API Sandbox", "https://rest.apisandbox.zuora.com"),
                            option("US Cloud Production", "https://rest.na.zuora.com"),
                            option("EU Production", "https://rest.eu.zuora.com"))
                        .defaultValue("https://rest.zuora.com")
                        .required(true),
                    string(CLIENT_ID)
                        .label("Client Id")
                        .description("The client ID of your Zuora OAuth client.")
                        .required(true),
                    string(CLIENT_SECRET)
                        .label("Client Secret")
                        .description("The client secret of your Zuora OAuth client.")
                        .required(true))
                .tokenUrl((connectionParameters, context) -> connectionParameters.getString(
                    ENVIRONMENT, "https://rest.zuora.com") + "/oauth/token"));

    private ZuoraConnection() {
    }
}

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

package com.bytechef.component.paypal.connection;

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
public class PaypalConnection {

    public static final String ENVIRONMENT = "environment";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getString(
            ENVIRONMENT, "https://api-m.paypal.com"))
        .authorizations(
            authorization(AuthorizationType.OAUTH2_CLIENT_CREDENTIALS)
                .title("OAuth2 Client Credentials")
                .properties(
                    string(ENVIRONMENT)
                        .label("Environment")
                        .description("The PayPal environment to connect to.")
                        .options(
                            option("Live", "https://api-m.paypal.com"),
                            option("Sandbox", "https://api-m.sandbox.paypal.com"))
                        .defaultValue("https://api-m.paypal.com")
                        .required(true),
                    string(CLIENT_ID)
                        .label("Client Id")
                        .description("The client ID of your PayPal REST API app.")
                        .required(true),
                    string(CLIENT_SECRET)
                        .label("Client Secret")
                        .description("The client secret of your PayPal REST API app.")
                        .required(true))
                .tokenUrl((connectionParameters, context) -> connectionParameters.getString(
                    ENVIRONMENT, "https://api-m.paypal.com") + "/v1/oauth2/token"));

    private PaypalConnection() {
    }
}

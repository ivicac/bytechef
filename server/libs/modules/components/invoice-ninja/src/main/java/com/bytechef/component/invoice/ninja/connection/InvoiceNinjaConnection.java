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

package com.bytechef.component.invoice.ninja.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.invoice.ninja.constant.InvoiceNinjaConstants.API_TOKEN;
import static com.bytechef.component.invoice.ninja.constant.InvoiceNinjaConstants.DOMAIN;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class InvoiceNinjaConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "%s/api/v1"
            .formatted(connectionParameters.getRequiredString(DOMAIN)))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Token")
                .properties(
                    string(DOMAIN)
                        .label("Domain")
                        .description("The base URL of your Invoice Ninja instance.")
                        .defaultValue("https://invoicing.co")
                        .required(true),
                    string(API_TOKEN)
                        .label("API Token")
                        .description("The API token created in your Invoice Ninja account settings.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of("X-API-Token", List.of(connectionParameters.getString(API_TOKEN))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/invoiceNinja_v1#connection-setup");

    private InvoiceNinjaConnection() {
    }
}

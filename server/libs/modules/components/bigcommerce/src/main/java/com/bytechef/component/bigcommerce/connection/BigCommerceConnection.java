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

package com.bytechef.component.bigcommerce.connection;

import static com.bytechef.component.bigcommerce.constant.BigCommerceConstants.ACCESS_TOKEN;
import static com.bytechef.component.bigcommerce.constant.BigCommerceConstants.STORE_HASH;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BigCommerceConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.bigcommerce.com/stores/%s/v3"
            .formatted(connectionParameters.getRequiredString(STORE_HASH)))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(STORE_HASH)
                        .label("Store Hash")
                        .description("The store hash from your store's API path.")
                        .required(true),
                    string(ACCESS_TOKEN)
                        .label("Access Token")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of("X-Auth-Token", List.of(connectionParameters.getString(ACCESS_TOKEN))))));

    private BigCommerceConnection() {
    }
}

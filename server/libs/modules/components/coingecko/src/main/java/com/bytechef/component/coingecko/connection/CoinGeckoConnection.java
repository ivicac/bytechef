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

package com.bytechef.component.coingecko.connection;

import static com.bytechef.component.coingecko.constant.CoinGeckoConstants.API_KEY;
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
public class CoinGeckoConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.coingecko.com/api/v3")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(API_KEY)
                        .label("Demo API Key")
                        .description(
                            "The demo API key created in your CoinGecko developer dashboard. Optional for low " +
                                "request volumes.")
                        .required(false))
                .apply((connectionParameters, context) -> {
                    String apiKey = connectionParameters.getString(API_KEY);

                    if (apiKey == null || apiKey.isEmpty()) {
                        return ApplyResponse.ofHeaders(Map.of());
                    }

                    return ApplyResponse.ofHeaders(Map.of("x-cg-demo-api-key", List.of(apiKey)));
                }))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/coinGecko_v1#connection-setup");

    private CoinGeckoConnection() {
    }
}

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

package com.bytechef.component.harvest.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.harvest.constant.HarvestConstants.ACCOUNT_ID;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class HarvestConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.harvestapp.com/v2")
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("Personal Access Token")
                .properties(
                    string(TOKEN)
                        .label("Personal Access Token")
                        .description("The personal access token created in the Harvest developer tools.")
                        .required(true),
                    string(ACCOUNT_ID)
                        .label("Account ID")
                        .description("The Harvest account id shown next to the personal access token.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "Authorization", List.of("Bearer " + connectionParameters.getString(TOKEN)),
                        "Harvest-Account-Id", List.of(connectionParameters.getString(ACCOUNT_ID))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/harvest_v1#connection-setup");

    private HarvestConnection() {
    }
}

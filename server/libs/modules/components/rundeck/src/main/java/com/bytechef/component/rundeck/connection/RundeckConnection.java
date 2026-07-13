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

package com.bytechef.component.rundeck.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.rundeck.constant.RundeckConstants.TOKEN;
import static com.bytechef.component.rundeck.constant.RundeckConstants.URL;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class RundeckConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url + "/api/41";
        })
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Token")
                .properties(
                    string(URL)
                        .label("URL")
                        .description("The URL of your Rundeck instance (e.g. https://rundeck.example.com:4440).")
                        .required(true),
                    string(TOKEN)
                        .label("API Token")
                        .description("The API token created in Rundeck under User Profile.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of("X-Rundeck-Auth-Token", List.of(connectionParameters.getRequiredString(TOKEN))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/rundeck_v1#connection-setup");

    private RundeckConnection() {
    }
}

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

package com.bytechef.component.onepassword.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.onepassword.constant.OnePasswordConstants.URL;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class OnePasswordConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url + "/v1";
        })
        .authorizations(
            authorization(AuthorizationType.BEARER_TOKEN)
                .title("Connect Token")
                .properties(
                    string(URL)
                        .label("Connect Server URL")
                        .description(
                            "The URL of your 1Password Connect server (e.g. http://localhost:8080).")
                        .required(true),
                    string(TOKEN)
                        .label("Connect Token")
                        .description("The access token issued for the 1Password Connect server.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/onePassword_v1#connection-setup");

    private OnePasswordConnection() {
    }
}

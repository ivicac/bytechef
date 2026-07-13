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

package com.bytechef.component.discourse.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.discourse.constant.DiscourseConstants.API_KEY;
import static com.bytechef.component.discourse.constant.DiscourseConstants.DOMAIN;
import static com.bytechef.component.discourse.constant.DiscourseConstants.USERNAME;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class DiscourseConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getRequiredString(DOMAIN))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(DOMAIN)
                        .label("Domain")
                        .description("The base URL of your Discourse instance, e.g. https://forum.example.com.")
                        .required(true),
                    string(API_KEY)
                        .label("API Key")
                        .description("The API key created in your Discourse admin settings.")
                        .required(true),
                    string(USERNAME)
                        .label("Username")
                        .description("The username the API key acts on behalf of.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "Api-Key", List.of(connectionParameters.getString(API_KEY)),
                        "Api-Username", List.of(connectionParameters.getString(USERNAME))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/discourse_v1#connection-setup");

    private DiscourseConnection() {
    }
}

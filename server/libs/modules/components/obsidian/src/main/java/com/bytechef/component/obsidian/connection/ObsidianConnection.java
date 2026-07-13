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

package com.bytechef.component.obsidian.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class ObsidianConnection {

    public static final String BASE_URL = "baseUrl";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getRequiredString(BASE_URL))
        .authorizations(
            authorization(AuthorizationType.BEARER_TOKEN)
                .title("API Key")
                .properties(
                    string(BASE_URL)
                        .label("Base URL")
                        .description(
                            "The URL of the Obsidian Local REST API plugin, e.g. https://127.0.0.1:27124.")
                        .required(true),
                    string(TOKEN)
                        .label("API Key")
                        .description("The API key shown in the Local REST API plugin settings.")
                        .required(true)));

    private ObsidianConnection() {
    }
}

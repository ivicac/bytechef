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

package com.bytechef.component.home.assistant.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.home.assistant.constant.HomeAssistantConstants.URL;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class HomeAssistantConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url + "/api";
        })
        .authorizations(
            authorization(AuthorizationType.BEARER_TOKEN)
                .title("Long-Lived Access Token")
                .properties(
                    string(URL)
                        .label("URL")
                        .description(
                            "The URL of your Home Assistant instance (e.g. http://homeassistant.local:8123).")
                        .required(true),
                    string(TOKEN)
                        .label("Long-Lived Access Token")
                        .description("The long-lived access token created in your Home Assistant profile.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/homeAssistant_v1#connection-setup");

    private HomeAssistantConnection() {
    }
}

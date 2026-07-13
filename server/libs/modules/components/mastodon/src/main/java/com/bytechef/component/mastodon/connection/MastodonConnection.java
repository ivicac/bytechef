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

package com.bytechef.component.mastodon.connection;

import static com.bytechef.component.definition.Authorization.TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class MastodonConnection {

    public static final String INSTANCE_URL = "instanceUrl";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getRequiredString(INSTANCE_URL))
        .authorizations(
            authorization(AuthorizationType.BEARER_TOKEN)
                .title("Access Token")
                .properties(
                    string(INSTANCE_URL)
                        .label("Instance URL")
                        .description("The URL of your Mastodon instance, e.g. https://mastodon.social.")
                        .defaultValue("https://mastodon.social")
                        .required(true),
                    string(TOKEN)
                        .label("Access Token")
                        .description(
                            "The access token created in your Mastodon instance under Preferences -> Development.")
                        .required(true)));

    private MastodonConnection() {
    }
}

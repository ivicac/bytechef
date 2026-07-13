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

package com.bytechef.component.nextcloud.connection;

import static com.bytechef.component.definition.Authorization.PASSWORD;
import static com.bytechef.component.definition.Authorization.USERNAME;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.nextcloud.constant.NextcloudConstants.URL;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class NextcloudConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url;
        })
        .authorizations(
            authorization(AuthorizationType.BASIC_AUTH)
                .title("Basic Authentication")
                .properties(
                    string(URL)
                        .label("URL")
                        .description("The URL of your Nextcloud instance (e.g. https://cloud.example.com).")
                        .required(true),
                    string(USERNAME)
                        .label("Username")
                        .description("The username of your Nextcloud user.")
                        .required(true),
                    string(PASSWORD)
                        .label("App Password")
                        .description(
                            "The app password created in Nextcloud under Settings → Security, or the user password.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/nextcloud_v1#connection-setup");

    private NextcloudConnection() {
    }
}

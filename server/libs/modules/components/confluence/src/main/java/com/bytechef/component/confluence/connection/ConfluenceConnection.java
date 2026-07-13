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

package com.bytechef.component.confluence.connection;

import static com.bytechef.component.confluence.constant.ConfluenceConstants.DOMAIN;
import static com.bytechef.component.definition.Authorization.PASSWORD;
import static com.bytechef.component.definition.Authorization.USERNAME;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class ConfluenceConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://%s.atlassian.net/wiki/api/v2"
            .formatted(connectionParameters.getRequiredString(DOMAIN)))
        .authorizations(
            authorization(AuthorizationType.BASIC_AUTH)
                .title("Basic Authentication")
                .properties(
                    string(DOMAIN)
                        .label("Domain")
                        .description("The domain of your Confluence site (https://DOMAIN.atlassian.net).")
                        .required(true),
                    string(USERNAME)
                        .label("Email")
                        .description("The email address of your Atlassian account.")
                        .required(true),
                    string(PASSWORD)
                        .label("API Token")
                        .description("The API token created in your Atlassian account security settings.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/confluence_v1#connection-setup");

    private ConfluenceConnection() {
    }
}

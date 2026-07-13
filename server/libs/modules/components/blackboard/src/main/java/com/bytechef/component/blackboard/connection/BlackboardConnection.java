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

package com.bytechef.component.blackboard.connection;

import static com.bytechef.component.blackboard.constant.BlackboardConstants.INSTANCE_URL;
import static com.bytechef.component.definition.Authorization.CLIENT_ID;
import static com.bytechef.component.definition.Authorization.CLIENT_SECRET;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class BlackboardConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String instanceUrl = connectionParameters.getRequiredString(INSTANCE_URL);

            if (instanceUrl.endsWith("/")) {
                instanceUrl = instanceUrl.substring(0, instanceUrl.length() - 1);
            }

            return instanceUrl;
        })
        .authorizations(
            authorization(AuthorizationType.OAUTH2_CLIENT_CREDENTIALS)
                .title("OAuth2 Client Credentials")
                .properties(
                    string(INSTANCE_URL)
                        .label("Instance URL")
                        .description(
                            "The URL of your Blackboard Learn instance (e.g. https://mycompany.blackboard.com).")
                        .required(true),
                    string(CLIENT_ID)
                        .label("Application Key")
                        .description("The application key of the REST API integration.")
                        .required(true),
                    string(CLIENT_SECRET)
                        .label("Application Secret")
                        .description("The application secret of the REST API integration.")
                        .required(true))
                .tokenUrl((connectionParameters, context) -> connectionParameters.getRequiredString(INSTANCE_URL)
                    + "/learn/api/public/v1/oauth2/token"))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/blackboard_v1#connection-setup");

    private BlackboardConnection() {
    }
}

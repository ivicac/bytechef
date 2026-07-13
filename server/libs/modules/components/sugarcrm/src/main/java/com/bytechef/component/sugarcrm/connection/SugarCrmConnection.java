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

package com.bytechef.component.sugarcrm.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.PASSWORD;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.PLATFORM;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.SERVER_URL;
import static com.bytechef.component.sugarcrm.constant.SugarCrmConstants.USERNAME;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class SugarCrmConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String serverUrl = connectionParameters.getRequiredString(SERVER_URL);

            if (serverUrl.endsWith("/")) {
                serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
            }

            return serverUrl + "/rest/v11";
        })
        .authorizations(
            authorization(AuthorizationType.CUSTOM)
                .title("Username and Password")
                .properties(
                    string(SERVER_URL)
                        .label("Server URL")
                        .description("The URL of your SugarCRM instance (e.g. https://mycompany.sugarondemand.com).")
                        .required(true),
                    string(USERNAME)
                        .label("Username")
                        .description("The username of your SugarCRM user.")
                        .required(true),
                    string(PASSWORD)
                        .label("Password")
                        .description("The password of your SugarCRM user.")
                        .required(true),
                    string(PLATFORM)
                        .label("Platform")
                        .description("The API platform registered in SugarCRM (defaults to base).")
                        .defaultValue("base")
                        .required(false)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/sugarCrm_v1#connection-setup");

    private SugarCrmConnection() {
    }
}

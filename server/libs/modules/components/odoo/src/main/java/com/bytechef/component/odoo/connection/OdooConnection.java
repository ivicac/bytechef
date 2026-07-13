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

package com.bytechef.component.odoo.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.odoo.constant.OdooConstants.API_KEY;
import static com.bytechef.component.odoo.constant.OdooConstants.DATABASE;
import static com.bytechef.component.odoo.constant.OdooConstants.URL;
import static com.bytechef.component.odoo.constant.OdooConstants.USERNAME;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class OdooConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url;
        })
        .authorizations(
            authorization(AuthorizationType.CUSTOM)
                .title("API Key")
                .properties(
                    string(URL)
                        .label("URL")
                        .description("The URL of your Odoo instance (e.g. https://mycompany.odoo.com).")
                        .required(true),
                    string(DATABASE)
                        .label("Database")
                        .description("The name of the Odoo database.")
                        .required(true),
                    string(USERNAME)
                        .label("Username")
                        .description("The login (email) of your Odoo user.")
                        .required(true),
                    string(API_KEY)
                        .label("API Key")
                        .description(
                            "The API key created in Odoo under Settings → My Profile → Account Security, or the " +
                                "user password.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/odoo_v1#connection-setup");

    private OdooConnection() {
    }
}

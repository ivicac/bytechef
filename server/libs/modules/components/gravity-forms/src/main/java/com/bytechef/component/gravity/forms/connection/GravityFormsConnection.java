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

package com.bytechef.component.gravity.forms.connection;

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
public class GravityFormsConnection {

    public static final String SITE_URL = "siteUrl";

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> connectionParameters.getRequiredString(SITE_URL)
            + "/wp-json/gf/v2")
        .authorizations(
            authorization(AuthorizationType.BASIC_AUTH)
                .title("Basic Auth")
                .properties(
                    string(SITE_URL)
                        .label("Site URL")
                        .description("The URL of your WordPress site, e.g. https://example.com.")
                        .required(true),
                    string(USERNAME)
                        .label("Consumer Key")
                        .description("The consumer key created in Gravity Forms under Settings -> REST API.")
                        .required(true),
                    string(PASSWORD)
                        .label("Consumer Secret")
                        .description("The consumer secret created in Gravity Forms under Settings -> REST API.")
                        .required(true)));

    private GravityFormsConnection() {
    }
}

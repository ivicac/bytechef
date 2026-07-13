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

package com.bytechef.component.agencyzoom.connection;

import static com.bytechef.component.agencyzoom.constant.AgencyZoomConstants.PASSWORD;
import static com.bytechef.component.agencyzoom.constant.AgencyZoomConstants.USERNAME;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class AgencyZoomConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.agencyzoom.com/v1/api")
        .authorizations(
            authorization(AuthorizationType.CUSTOM)
                .title("Username and Password")
                .properties(
                    string(USERNAME)
                        .label("Username")
                        .description("The email address used to log in to AgencyZoom.")
                        .required(true),
                    string(PASSWORD)
                        .label("Password")
                        .description("The password used to log in to AgencyZoom.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/agencyZoom_v1#connection-setup");

    private AgencyZoomConnection() {
    }
}

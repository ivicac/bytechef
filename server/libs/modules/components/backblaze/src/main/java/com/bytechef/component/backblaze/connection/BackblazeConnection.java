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

package com.bytechef.component.backblaze.connection;

import static com.bytechef.component.backblaze.constant.BackblazeConstants.APPLICATION_KEY;
import static com.bytechef.component.backblaze.constant.BackblazeConstants.KEY_ID;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class BackblazeConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .authorizations(
            authorization(AuthorizationType.CUSTOM)
                .title("Application Key")
                .properties(
                    string(KEY_ID)
                        .label("Key Id")
                        .description("The id of the Backblaze B2 application key.")
                        .required(true),
                    string(APPLICATION_KEY)
                        .label("Application Key")
                        .description("The Backblaze B2 application key.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/backblaze_v1#connection-setup");

    private BackblazeConnection() {
    }
}

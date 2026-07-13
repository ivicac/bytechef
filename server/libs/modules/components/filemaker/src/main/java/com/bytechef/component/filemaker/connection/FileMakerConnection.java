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

package com.bytechef.component.filemaker.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.DATABASE;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.HOST;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.PASSWORD;
import static com.bytechef.component.filemaker.constant.FileMakerConstants.USERNAME;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class FileMakerConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String host = connectionParameters.getRequiredString(HOST);

            if (host.endsWith("/")) {
                host = host.substring(0, host.length() - 1);
            }

            return host + "/fmi/data/v1";
        })
        .authorizations(
            authorization(AuthorizationType.CUSTOM)
                .title("Username and Password")
                .properties(
                    string(HOST)
                        .label("Host")
                        .description("The URL of your FileMaker Server (e.g. https://myserver.example.com).")
                        .required(true),
                    string(DATABASE)
                        .label("Database")
                        .description("The name of the hosted FileMaker database.")
                        .required(true),
                    string(USERNAME)
                        .label("Username")
                        .description("The FileMaker account name with the fmrest extended privilege.")
                        .required(true),
                    string(PASSWORD)
                        .label("Password")
                        .description("The password of the FileMaker account.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/fileMaker_v1#connection-setup");

    private FileMakerConnection() {
    }
}

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

package com.bytechef.component.uptimerobot.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.uptimerobot.constant.UptimeRobotConstants.API_KEY;

import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Ivica Cardic
 */
public class UptimeRobotConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.uptimerobot.com/v2")
        .authorizations(
            authorization(AuthorizationType.CUSTOM)
                .title("API Key")
                .properties(
                    string(API_KEY)
                        .label("API Key")
                        .description(
                            "The main API key found in your UptimeRobot integrations settings. It is sent in the " +
                                "request body as required by the UptimeRobot API.")
                        .required(true)))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/uptimerobot_v1#connection-setup");

    private UptimeRobotConnection() {
    }
}

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

package com.bytechef.component.clickhouse.connection;

import static com.bytechef.component.clickhouse.constant.ClickHouseConstants.PASSWORD;
import static com.bytechef.component.clickhouse.constant.ClickHouseConstants.URL;
import static com.bytechef.component.clickhouse.constant.ClickHouseConstants.USERNAME;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ClickHouseConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url;
        })
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("Username and Password")
                .properties(
                    string(URL)
                        .label("URL")
                        .description(
                            "The HTTP interface URL of your ClickHouse server (e.g. " +
                                "https://myinstance.clickhouse.cloud:8443).")
                        .required(true),
                    string(USERNAME)
                        .label("Username")
                        .description("The username of the ClickHouse user.")
                        .required(true),
                    string(PASSWORD)
                        .label("Password")
                        .description("The password of the ClickHouse user.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "X-ClickHouse-User", List.of(connectionParameters.getRequiredString(USERNAME)),
                        "X-ClickHouse-Key", List.of(connectionParameters.getRequiredString(PASSWORD))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/clickHouse_v1#connection-setup");

    private ClickHouseConnection() {
    }
}

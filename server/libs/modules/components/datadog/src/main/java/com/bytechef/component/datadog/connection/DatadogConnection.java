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

package com.bytechef.component.datadog.connection;

import static com.bytechef.component.datadog.constant.DatadogConstants.API_KEY;
import static com.bytechef.component.datadog.constant.DatadogConstants.APPLICATION_KEY;
import static com.bytechef.component.datadog.constant.DatadogConstants.SITE;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class DatadogConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://api.%s"
            .formatted(connectionParameters.getRequiredString(SITE)))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(SITE)
                        .label("Site")
                        .description("The Datadog site of your organization.")
                        .options(
                            option("US1 (datadoghq.com)", "datadoghq.com"),
                            option("US3 (us3.datadoghq.com)", "us3.datadoghq.com"),
                            option("US5 (us5.datadoghq.com)", "us5.datadoghq.com"),
                            option("EU1 (datadoghq.eu)", "datadoghq.eu"),
                            option("AP1 (ap1.datadoghq.com)", "ap1.datadoghq.com"))
                        .defaultValue("datadoghq.com")
                        .required(true),
                    string(API_KEY)
                        .label("API Key")
                        .description("The API key created in your Datadog organization settings.")
                        .required(true),
                    string(APPLICATION_KEY)
                        .label("Application Key")
                        .description("The application key created in your Datadog organization settings.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "DD-API-KEY", List.of(connectionParameters.getString(API_KEY)),
                        "DD-APPLICATION-KEY", List.of(connectionParameters.getString(APPLICATION_KEY))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/datadog_v1#connection-setup");

    private DatadogConnection() {
    }
}

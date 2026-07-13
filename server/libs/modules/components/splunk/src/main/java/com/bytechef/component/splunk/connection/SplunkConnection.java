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

package com.bytechef.component.splunk.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.splunk.constant.SplunkConstants.TOKEN;
import static com.bytechef.component.splunk.constant.SplunkConstants.URL;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class SplunkConnection {

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
                .title("HEC Token")
                .properties(
                    string(URL)
                        .label("HEC URL")
                        .description(
                            "The URL of the Splunk HTTP Event Collector (e.g. " +
                                "https://mysplunk.example.com:8088).")
                        .required(true),
                    string(TOKEN)
                        .label("HEC Token")
                        .description("The HTTP Event Collector token created in Splunk.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of("Authorization", List.of("Splunk " + connectionParameters.getRequiredString(TOKEN))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/splunk_v1#connection-setup");

    private SplunkConnection() {
    }
}

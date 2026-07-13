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

package com.bytechef.component.erpnext.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.erpnext.constant.ErpNextConstants.API_KEY;
import static com.bytechef.component.erpnext.constant.ErpNextConstants.API_SECRET;
import static com.bytechef.component.erpnext.constant.ErpNextConstants.URL;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ErpNextConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> {
            String url = connectionParameters.getRequiredString(URL);

            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            return url + "/api";
        })
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key and Secret")
                .properties(
                    string(URL)
                        .label("URL")
                        .description("The URL of your ERPNext site (e.g. https://mycompany.erpnext.com).")
                        .required(true),
                    string(API_KEY)
                        .label("API Key")
                        .description("The API key generated for your ERPNext user.")
                        .required(true),
                    string(API_SECRET)
                        .label("API Secret")
                        .description("The API secret generated for your ERPNext user.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of(
                        "Authorization",
                        List.of(
                            "token " + connectionParameters.getRequiredString(API_KEY) + ":" +
                                connectionParameters.getRequiredString(API_SECRET))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/erpNext_v1#connection-setup");

    private ErpNextConnection() {
    }
}

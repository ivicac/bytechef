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

package com.bytechef.component.azure.blob.storage.connection;

import static com.bytechef.component.azure.blob.storage.constant.AzureBlobStorageConstants.ACCOUNT;
import static com.bytechef.component.azure.blob.storage.constant.AzureBlobStorageConstants.SAS_TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class AzureBlobStorageConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://%s.blob.core.windows.net"
            .formatted(connectionParameters.getRequiredString(ACCOUNT)))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("SAS Token")
                .properties(
                    string(ACCOUNT)
                        .label("Storage Account")
                        .description("The name of the Azure storage account.")
                        .required(true),
                    string(SAS_TOKEN)
                        .label("SAS Token")
                        .description(
                            "The shared access signature (SAS) token of the storage account, without the leading " +
                                "question mark.")
                        .required(true))
                .apply((connectionParameters, context) -> {
                    String sasToken = connectionParameters.getRequiredString(SAS_TOKEN);

                    if (sasToken.startsWith("?")) {
                        sasToken = sasToken.substring(1);
                    }

                    Map<String, List<String>> queryParameters = new HashMap<>();

                    for (String parameter : sasToken.split("&")) {
                        int index = parameter.indexOf('=');

                        if (index > 0) {
                            queryParameters.put(
                                parameter.substring(0, index), List.of(parameter.substring(index + 1)));
                        }
                    }

                    return ApplyResponse.ofQueryParameters(queryParameters);
                }))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/azureBlobStorage_v1#connection-setup");

    private AzureBlobStorageConnection() {
    }
}

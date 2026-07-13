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

package com.bytechef.component.recall.ai.connection;

import static com.bytechef.component.definition.Authorization.API_TOKEN;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.recall.ai.constant.RecallAiConstants.REGION;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class RecallAiConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .baseUri((connectionParameters, context) -> "https://%s.recall.ai/api/v1".formatted(
            connectionParameters.getRequiredString(REGION)))
        .authorizations(
            authorization(AuthorizationType.API_KEY)
                .title("API Key")
                .properties(
                    string(API_TOKEN)
                        .label("API Key")
                        .description("The API key generated in your Recall.ai dashboard.")
                        .required(true),
                    string(REGION)
                        .label("Region")
                        .description("The region of your Recall.ai workspace.")
                        .options(
                            option("US (us-east-1)", "us-east-1"),
                            option("EU (eu-central-1)", "eu-central-1"),
                            option("Japan (ap-northeast-1)", "ap-northeast-1"))
                        .defaultValue("us-east-1")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of("Authorization", List.of(connectionParameters.getString(API_TOKEN))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/recallAi_v1#connection-setup");

    private RecallAiConnection() {
    }
}

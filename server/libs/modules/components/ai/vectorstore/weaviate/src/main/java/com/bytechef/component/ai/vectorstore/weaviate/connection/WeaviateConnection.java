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

package com.bytechef.component.ai.vectorstore.weaviate.connection;

import static com.bytechef.component.ai.vectorstore.weaviate.constant.WeaviateConstants.API_KEY;
import static com.bytechef.component.ai.vectorstore.weaviate.constant.WeaviateConstants.URL;
import static com.bytechef.component.definition.Authorization.AuthorizationType.CUSTOM;
import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;

/**
 * @author Monika Kušter
 */
public class WeaviateConnection {

    public static final ModifiableConnectionDefinition CONNECTION_DEFINITION = connection()
        .authorizations(
            authorization(CUSTOM)
                .properties(
                    string(URL)
                        .label("Weaviate Url")
                        .description("The URL of the Weaviate instance.")
                        .exampleValue("https://jahgfduszftiq.c0.europe-west3.gcp.weaviate.cloud")
                        .required(true),
                    string(API_KEY)
                        .label("Weaviate API Key")
                        .description("The API key for the Weaviate API.")
                        .required(true)));

    private WeaviateConnection() {
    }
}

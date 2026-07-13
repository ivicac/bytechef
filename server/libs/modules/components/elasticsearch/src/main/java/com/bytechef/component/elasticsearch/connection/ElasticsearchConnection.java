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

package com.bytechef.component.elasticsearch.connection;

import static com.bytechef.component.definition.ComponentDsl.authorization;
import static com.bytechef.component.definition.ComponentDsl.connection;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.elasticsearch.constant.ElasticsearchConstants.API_KEY;
import static com.bytechef.component.elasticsearch.constant.ElasticsearchConstants.URL;

import com.bytechef.component.definition.Authorization.ApplyResponse;
import com.bytechef.component.definition.Authorization.AuthorizationType;
import com.bytechef.component.definition.ComponentDsl.ModifiableConnectionDefinition;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ElasticsearchConnection {

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
                .title("API Key")
                .properties(
                    string(URL)
                        .label("URL")
                        .description(
                            "The URL of your Elasticsearch cluster (e.g. https://mycluster.es.us-east-1.aws.found.io).")
                        .required(true),
                    string(API_KEY)
                        .label("API Key")
                        .description("The Base64 encoded API key created in Elasticsearch or Kibana.")
                        .required(true))
                .apply((connectionParameters, context) -> ApplyResponse.ofHeaders(
                    Map.of("Authorization", List.of("ApiKey " + connectionParameters.getRequiredString(API_KEY))))))
        .version(1)
        .help("", "https://docs.bytechef.io/reference/components/elasticsearch_v1#connection-setup");

    private ElasticsearchConnection() {
    }
}

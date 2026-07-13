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

package com.bytechef.component.elasticsearch.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.elasticsearch.constant.ElasticsearchConstants.INDEX;
import static com.bytechef.component.elasticsearch.constant.ElasticsearchConstants.QUERY;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;

/**
 * @author Ivica Cardic
 */
public class ElasticsearchSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Searches documents of the index using the Elasticsearch query DSL.")
        .properties(
            string(INDEX)
                .label("Index")
                .description("The name of the index to search.")
                .required(true),
            string(QUERY)
                .label("Query")
                .description(
                    "The search request body as JSON using the Elasticsearch query DSL. If empty, all documents " +
                        "are matched.")
                .controlType(ControlType.TEXT_AREA)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("hits")
                            .description("The search hits."))))
        .help("", "https://docs.bytechef.io/reference/components/elasticsearch_v1#search")
        .perform(ElasticsearchSearchAction::perform);

    private ElasticsearchSearchAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String query = inputParameters.getString(QUERY, "{\"query\":{\"match_all\":{}}}");

        return context
            .http(http -> http.post("/%s/_search".formatted(inputParameters.getRequiredString(INDEX))))
            .body(Body.of(query, "application/json"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

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

package com.bytechef.component.algolia.action;

import static com.bytechef.component.algolia.constant.AlgoliaConstants.INDEX_NAME;
import static com.bytechef.component.algolia.constant.AlgoliaConstants.QUERY;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class AlgoliaSearchIndexAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("searchIndex")
        .title("Search Index")
        .description("Searches an index for matching records.")
        .properties(
            string(INDEX_NAME)
                .label("Index Name")
                .description("The name of the index that will be searched.")
                .required(true),
            string(QUERY)
                .label("Query")
                .description("The search query.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("hits")
                            .description("The matching records.")
                            .items(object()),
                        integer("nbHits")
                            .description("The number of matching records."),
                        integer("page")
                            .description("The current page of results."),
                        integer("nbPages")
                            .description("The total number of result pages."),
                        string("query")
                            .description("The query that was searched for."))))
        .help("", "https://docs.bytechef.io/reference/components/algolia_v1#search-index")
        .perform(AlgoliaSearchIndexAction::perform);

    private AlgoliaSearchIndexAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/indexes/%s/query".formatted(inputParameters.getRequiredString(INDEX_NAME))))
            .body(Body.of(Map.of(QUERY, inputParameters.getRequiredString(QUERY))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

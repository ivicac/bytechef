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

package com.bytechef.component.exa.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.exa.constant.ExaConstants.NUM_RESULTS;
import static com.bytechef.component.exa.constant.ExaConstants.QUERY;
import static com.bytechef.component.exa.constant.ExaConstants.TYPE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ExaSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Performs an AI-powered web search and returns the most relevant results.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The search query.")
                .required(true),
            string(TYPE)
                .label("Search Type")
                .description("The type of search to perform.")
                .options(
                    option("Auto", "auto"),
                    option("Neural", "neural"),
                    option("Keyword", "keyword"))
                .defaultValue("auto")
                .required(false),
            integer(NUM_RESULTS)
                .label("Number of Results")
                .description("The number of results to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("requestId")
                            .description("The id of the request."),
                        array("results")
                            .description("The search results.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the result."),
                                        string("title")
                                            .description("The title of the result."),
                                        string("url")
                                            .description("The URL of the result."),
                                        string("publishedDate")
                                            .description("The published date of the result."),
                                        string("author")
                                            .description("The author of the result."),
                                        number("score")
                                            .description("The relevance score of the result."))))))
        .help("", "https://docs.bytechef.io/reference/components/exa_v1#search")
        .perform(ExaSearchAction::perform);

    private ExaSearchAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(QUERY, inputParameters.getRequiredString(QUERY));

        String type = inputParameters.getString(TYPE);

        if (type != null) {
            body.put(TYPE, type);
        }

        Integer numResults = inputParameters.getInteger(NUM_RESULTS);

        if (numResults != null) {
            body.put(NUM_RESULTS, numResults);
        }

        return context.http(http -> http.post("/search"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

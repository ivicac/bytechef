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

package com.bytechef.component.linkup.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.bool;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.linkup.constant.LinkupConstants.DEPTH;
import static com.bytechef.component.linkup.constant.LinkupConstants.INCLUDE_IMAGES;
import static com.bytechef.component.linkup.constant.LinkupConstants.OUTPUT_TYPE;
import static com.bytechef.component.linkup.constant.LinkupConstants.Q;

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
public class LinkupSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Searches the web for information and returns a sourced answer or raw search results.")
        .properties(
            string(Q)
                .label("Query")
                .description("The natural language question or search query.")
                .required(true),
            string(DEPTH)
                .label("Depth")
                .description(
                    "The precision of the search. Deep is slower but yields more complete results.")
                .options(
                    option("Standard", "standard"),
                    option("Deep", "deep"))
                .defaultValue("standard")
                .required(true),
            string(OUTPUT_TYPE)
                .label("Output Type")
                .description("The type of output that will be returned.")
                .options(
                    option("Sourced Answer", "sourcedAnswer"),
                    option("Search Results", "searchResults"))
                .defaultValue("sourcedAnswer")
                .required(true),
            bool(INCLUDE_IMAGES)
                .label("Include Images")
                .description("Whether image results should be included.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("answer")
                            .description("The answer to the query, when output type is sourced answer."),
                        array("sources")
                            .description("The sources used to build the answer.")
                            .items(
                                object()
                                    .properties(
                                        string("name")
                                            .description("The name of the source."),
                                        string("url")
                                            .description("The URL of the source."),
                                        string("snippet")
                                            .description("The snippet of the source."))),
                        array("results")
                            .description("The raw search results, when output type is search results.")
                            .items(
                                object()
                                    .properties(
                                        string("type")
                                            .description("The type of the result."),
                                        string("name")
                                            .description("The name of the result."),
                                        string("url")
                                            .description("The URL of the result."),
                                        string("content")
                                            .description("The content of the result."))))))
        .help("", "https://docs.bytechef.io/reference/components/linkup_v1#search")
        .perform(LinkupSearchAction::perform);

    private LinkupSearchAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(Q, inputParameters.getRequiredString(Q));
        body.put(DEPTH, inputParameters.getRequiredString(DEPTH));
        body.put(OUTPUT_TYPE, inputParameters.getRequiredString(OUTPUT_TYPE));

        Boolean includeImages = inputParameters.getBoolean(INCLUDE_IMAGES);

        if (includeImages != null) {
            body.put(INCLUDE_IMAGES, includeImages);
        }

        return context.http(http -> http.post("/search"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

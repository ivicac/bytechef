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

package com.bytechef.component.youcom.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.youcom.constant.YouComConstants.COUNTRY;
import static com.bytechef.component.youcom.constant.YouComConstants.NUM_WEB_RESULTS;
import static com.bytechef.component.youcom.constant.YouComConstants.QUERY;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class YouComSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Searches the web and returns results optimized for use with LLMs.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The search query.")
                .required(true),
            integer(NUM_WEB_RESULTS)
                .label("Number of Results")
                .description("The maximum number of web results to return.")
                .required(false),
            string(COUNTRY)
                .label("Country Code")
                .description("The two-letter country code for the search (e.g. US, DE).")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("hits")
                            .description("The search results.")
                            .items(
                                object()
                                    .properties(
                                        string("title")
                                            .description("The title of the result."),
                                        string("url")
                                            .description("The URL of the result."),
                                        string("description")
                                            .description("The description of the result."),
                                        array("snippets")
                                            .description("The text snippets of the result.")
                                            .items(string()))))))
        .help("", "https://docs.bytechef.io/reference/components/youcom_v1#search")
        .perform(YouComSearchAction::perform);

    private YouComSearchAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/search"))
            .queryParameters(
                QUERY, inputParameters.getRequiredString(QUERY),
                NUM_WEB_RESULTS, inputParameters.getInteger(NUM_WEB_RESULTS),
                COUNTRY, inputParameters.getString(COUNTRY))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

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

package com.bytechef.component.serpapi.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.ENGINE;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.GL;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.HL;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.LOCATION;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.NUM;
import static com.bytechef.component.serpapi.constant.SerpApiConstants.Q;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class SerpApiSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Google Search")
        .description("Searches Google and returns structured search results.")
        .properties(
            string(Q)
                .label("Query")
                .description("The search query.")
                .required(true),
            string(LOCATION)
                .label("Location")
                .description("The location from where the search should originate (e.g. Austin, Texas).")
                .required(false),
            string(GL)
                .label("Country Code")
                .description("The two-letter country code for the search (e.g. us, de).")
                .required(false),
            string(HL)
                .label("Language Code")
                .description("The two-letter language code for the search (e.g. en, de).")
                .required(false),
            integer(NUM)
                .label("Number of Results")
                .description("The maximum number of results to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("search_metadata")
                            .description("The metadata of the search.")
                            .properties(
                                string("id")
                                    .description("The id of the search."),
                                string("status")
                                    .description("The status of the search.")),
                        array("organic_results")
                            .description("The organic search results.")
                            .items(
                                object()
                                    .properties(
                                        integer("position")
                                            .description("The position of the result."),
                                        string("title")
                                            .description("The title of the result."),
                                        string("link")
                                            .description("The URL of the result."),
                                        string("snippet")
                                            .description("The snippet of the result."))))))
        .help("", "https://docs.bytechef.io/reference/components/serpapi_v1#google-search")
        .perform(SerpApiSearchAction::perform);

    private SerpApiSearchAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/search"))
            .queryParameters(
                ENGINE, "google",
                Q, inputParameters.getRequiredString(Q),
                LOCATION, inputParameters.getString(LOCATION),
                GL, inputParameters.getString(GL),
                HL, inputParameters.getString(HL),
                NUM, inputParameters.getInteger(NUM))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

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

package com.bytechef.component.wikipedia.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.wikipedia.constant.WikipediaConstants.LANGUAGE;
import static com.bytechef.component.wikipedia.constant.WikipediaConstants.LIMIT;
import static com.bytechef.component.wikipedia.constant.WikipediaConstants.QUERY;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class WikipediaSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Searches Wikipedia pages matching the query.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The search query.")
                .required(true),
            string(LANGUAGE)
                .label("Language")
                .description("The two-letter language code of the Wikipedia edition (e.g. en, de).")
                .defaultValue("en")
                .required(false),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of pages to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("pages")
                            .description("The pages matching the query.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The id of the page."),
                                        string("key")
                                            .description("The key of the page."),
                                        string("title")
                                            .description("The title of the page."),
                                        string("description")
                                            .description("The short description of the page."))))))
        .help("", "https://docs.bytechef.io/reference/components/wikipedia_v1#search")
        .perform(WikipediaSearchAction::perform);

    private WikipediaSearchAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String language = inputParameters.getString(LANGUAGE, "en");

        return context
            .http(http -> http.get("https://%s.wikipedia.org/w/rest.php/v1/search/page".formatted(language)))
            .queryParameters(
                "q", inputParameters.getRequiredString(QUERY),
                LIMIT, inputParameters.getInteger(LIMIT))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

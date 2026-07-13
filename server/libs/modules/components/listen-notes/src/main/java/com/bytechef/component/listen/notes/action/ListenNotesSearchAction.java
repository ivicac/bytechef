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

package com.bytechef.component.listen.notes.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.listen.notes.constant.ListenNotesConstants.LANGUAGE;
import static com.bytechef.component.listen.notes.constant.ListenNotesConstants.Q;
import static com.bytechef.component.listen.notes.constant.ListenNotesConstants.TYPE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class ListenNotesSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("search")
        .title("Search")
        .description("Searches for podcasts, episodes or curated lists of podcasts.")
        .properties(
            string(Q)
                .label("Query")
                .description("The search query.")
                .required(true),
            string(TYPE)
                .label("Type")
                .description("The type of content to search for.")
                .options(
                    option("Episode", "episode"),
                    option("Podcast", "podcast"),
                    option("Curated List", "curated"))
                .defaultValue("episode")
                .required(false),
            string(LANGUAGE)
                .label("Language")
                .description("The language of podcasts and episodes to search for (e.g. English).")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("count")
                            .description("The number of results in this page."),
                        integer("total")
                            .description("The total number of results."),
                        integer("next_offset")
                            .description("The offset for the next page of results."),
                        array("results")
                            .description("The search results.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the result."),
                                        string("title_original")
                                            .description("The title of the result."),
                                        string("description_original")
                                            .description("The description of the result."),
                                        string("listennotes_url")
                                            .description("The Listen Notes URL of the result."))))))
        .help("", "https://docs.bytechef.io/reference/components/listenNotes_v1#search")
        .perform(ListenNotesSearchAction::perform);

    private ListenNotesSearchAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/search"))
            .queryParameters(
                Q, inputParameters.getRequiredString(Q),
                TYPE, inputParameters.getString(TYPE),
                LANGUAGE, inputParameters.getString(LANGUAGE))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

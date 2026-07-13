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

package com.bytechef.component.jungle.scout.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.number;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.jungle.scout.constant.JungleScoutConstants.MARKETPLACE;
import static com.bytechef.component.jungle.scout.constant.JungleScoutConstants.SEARCH_TERMS;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class JungleScoutKeywordsByKeywordAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("keywordsByKeyword")
        .title("Keywords by Keyword")
        .description("Returns keyword metrics for keywords matching the search terms.")
        .properties(
            string(SEARCH_TERMS)
                .label("Search Terms")
                .description("The keyword to look up (e.g. yoga mat).")
                .required(true),
            string(MARKETPLACE)
                .label("Marketplace")
                .description("The Amazon marketplace to query.")
                .options(
                    option("United States", "us"), option("Canada", "ca"), option("Mexico", "mx"),
                    option("United Kingdom", "uk"), option("Germany", "de"), option("France", "fr"),
                    option("Italy", "it"), option("Spain", "es"), option("India", "in"), option("Japan", "jp"))
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .description("The keywords matching the search terms.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the keyword."),
                                        string("type")
                                            .description("The type of the returned record."),
                                        object("attributes")
                                            .description("The metrics of the keyword.")
                                            .properties(
                                                string("name")
                                                    .description("The keyword."),
                                                number("monthly_search_volume_exact")
                                                    .description("The exact-match monthly search volume."),
                                                number("monthly_search_volume_broad")
                                                    .description("The broad-match monthly search volume.")))))))
        .help("", "https://docs.bytechef.io/reference/components/jungleScout_v1#keywords-by-keyword")
        .perform(JungleScoutKeywordsByKeywordAction::perform);

    private JungleScoutKeywordsByKeywordAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/keywords/keywords_by_keyword_query"))
            .queryParameters(MARKETPLACE, inputParameters.getRequiredString(MARKETPLACE))
            .body(
                Body.of(
                    Map.of(
                        "data",
                        Map.of(
                            "type", "keywords_by_keyword_query_params",
                            "attributes", Map.of("search_terms", inputParameters.getRequiredString(SEARCH_TERMS))))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

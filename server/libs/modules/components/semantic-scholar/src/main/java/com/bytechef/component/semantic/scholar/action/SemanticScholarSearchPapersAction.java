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

package com.bytechef.component.semantic.scholar.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.semantic.scholar.constant.SemanticScholarConstants.FIELDS;
import static com.bytechef.component.semantic.scholar.constant.SemanticScholarConstants.LIMIT;
import static com.bytechef.component.semantic.scholar.constant.SemanticScholarConstants.QUERY;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class SemanticScholarSearchPapersAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("searchPapers")
        .title("Search Papers")
        .description("Searches for academic papers by relevance to the query.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The plain-text search query.")
                .required(true),
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of papers to return.")
                .required(false),
            string(FIELDS)
                .label("Fields")
                .description(
                    "A comma-separated list of fields to return for each paper (e.g. title,abstract,year,authors).")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("total")
                            .description("The total number of matching papers."),
                        integer("offset")
                            .description("The offset of the first returned paper."),
                        array("data")
                            .description("The matching papers.")
                            .items(
                                object()
                                    .properties(
                                        string("paperId")
                                            .description("The id of the paper."),
                                        string("title")
                                            .description("The title of the paper."),
                                        string("abstract")
                                            .description("The abstract of the paper."),
                                        integer("year")
                                            .description("The publication year of the paper."),
                                        array("authors")
                                            .description("The authors of the paper.")
                                            .items(
                                                object()
                                                    .properties(
                                                        string("authorId")
                                                            .description("The id of the author."),
                                                        string("name")
                                                            .description("The name of the author."))))))))
        .help("", "https://docs.bytechef.io/reference/components/semanticScholar_v1#search-papers")
        .perform(SemanticScholarSearchPapersAction::perform);

    private SemanticScholarSearchPapersAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/paper/search"))
            .queryParameters(
                QUERY, inputParameters.getRequiredString(QUERY),
                LIMIT, inputParameters.getInteger(LIMIT),
                FIELDS, inputParameters.getString(FIELDS))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

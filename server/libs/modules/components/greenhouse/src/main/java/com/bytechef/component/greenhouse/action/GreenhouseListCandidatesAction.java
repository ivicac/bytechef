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

package com.bytechef.component.greenhouse.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;

/**
 * @author Ivica Cardic
 */
public class GreenhouseListCandidatesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listCandidates")
        .title("List Candidates")
        .description("Returns the candidates in your Greenhouse organization.")
        .properties(
            integer("perPage")
                .label("Per Page")
                .description("The number of candidates to return per page.")
                .defaultValue(100)
                .required(false))
        .output(
            outputSchema(
                array()
                    .items(
                        object()
                            .properties(
                                integer("id"),
                                string("first_name"),
                                string("last_name"),
                                string("created_at")))))
        .perform(GreenhouseListCandidatesAction::perform);

    private GreenhouseListCandidatesAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.get("/candidates"))
            .queryParameters("per_page", inputParameters.getInteger("perPage", 100))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

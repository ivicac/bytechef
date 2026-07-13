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

package com.bytechef.component.ashby.action;

import static com.bytechef.component.ashby.constant.AshbyConstants.LIMIT;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.bool;
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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class AshbyListCandidatesAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listCandidates")
        .title("List Candidates")
        .description("Returns a list of candidates.")
        .properties(
            integer(LIMIT)
                .label("Limit")
                .description("The maximum number of candidates to return.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        bool("success")
                            .description("Whether the request was successful."),
                        array("results")
                            .description("The candidates.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the candidate."),
                                        string("name")
                                            .description("The name of the candidate."))))))
        .help("", "https://docs.bytechef.io/reference/components/ashby_v1#list-candidates")
        .perform(AshbyListCandidatesAction::perform);

    private AshbyListCandidatesAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        Integer limit = inputParameters.getInteger(LIMIT);

        if (limit != null) {
            body.put(LIMIT, limit);
        }

        return context
            .http(http -> http.post("/candidate.list"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

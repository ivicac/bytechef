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

package com.bytechef.component.dagster.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class DagsterExecuteGraphqlQueryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("executeGraphqlQuery")
        .title("Execute GraphQL Query")
        .description("Executes a GraphQL query against the Dagster GraphQL API.")
        .properties(
            string("query")
                .label("Query")
                .description("The GraphQL query, e.g. query { runsOrError { ... on Runs { results { runId } } } }.")
                .controlType(ControlType.TEXT_AREA)
                .required(true),
            object("variables")
                .label("Variables")
                .description("The GraphQL variables.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data"))))
        .perform(DagsterExecuteGraphqlQueryAction::perform);

    private DagsterExecuteGraphqlQueryAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("query", inputParameters.getRequiredString("query"));

        if (inputParameters.getMap("variables") != null) {
            body.put("variables", inputParameters.getMap("variables"));
        }

        return context.http(http -> http.post("/graphql"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

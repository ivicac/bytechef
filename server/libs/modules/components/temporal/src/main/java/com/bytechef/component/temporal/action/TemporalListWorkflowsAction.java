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

package com.bytechef.component.temporal.action;

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
import java.util.ArrayList;
import java.util.List;

/**
 * @author Ivica Cardic
 */
public class TemporalListWorkflowsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("listWorkflows")
        .title("List Workflows")
        .description("Returns the workflow executions in a namespace.")
        .properties(
            string("namespace")
                .label("Namespace")
                .description("The Temporal namespace.")
                .defaultValue("default")
                .required(true),
            string("query")
                .label("Query")
                .description("A Temporal visibility list filter, e.g. ExecutionStatus='Running'.")
                .required(false),
            integer("pageSize")
                .label("Page Size")
                .description("The number of workflow executions to return per page.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("executions")
                            .items(object()))))
        .perform(TemporalListWorkflowsAction::perform);

    private TemporalListWorkflowsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        List<Object> queryParameters = new ArrayList<>();

        if (inputParameters.getString("query") != null) {
            queryParameters.add("query");
            queryParameters.add(inputParameters.getString("query"));
        }

        if (inputParameters.getInteger("pageSize") != null) {
            queryParameters.add("pageSize");
            queryParameters.add(inputParameters.getInteger("pageSize"));
        }

        return context
            .http(http -> http.get(
                "/namespaces/%s/workflows".formatted(inputParameters.getRequiredString("namespace"))))
            .queryParameters(queryParameters.toArray())
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

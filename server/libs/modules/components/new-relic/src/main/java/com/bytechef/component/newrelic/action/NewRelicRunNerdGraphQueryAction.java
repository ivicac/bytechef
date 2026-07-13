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

package com.bytechef.component.newrelic.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.newrelic.constant.NewRelicConstants.QUERY;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class NewRelicRunNerdGraphQueryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("runNerdGraphQuery")
        .title("Run NerdGraph Query")
        .description("Runs a GraphQL query against the New Relic NerdGraph API.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The GraphQL query to run (e.g. { actor { user { name email } } }).")
                .controlType(ControlType.TEXT_AREA)
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The data returned by the query."))))
        .help("", "https://docs.bytechef.io/reference/components/newRelic_v1#run-nerdgraph-query")
        .perform(NewRelicRunNerdGraphQueryAction::perform);

    private NewRelicRunNerdGraphQueryAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/graphql"))
            .body(Body.of(Map.of(QUERY, inputParameters.getRequiredString(QUERY))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

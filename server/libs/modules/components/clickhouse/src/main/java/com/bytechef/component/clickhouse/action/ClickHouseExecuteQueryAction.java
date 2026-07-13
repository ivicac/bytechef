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

package com.bytechef.component.clickhouse.action;

import static com.bytechef.component.clickhouse.constant.ClickHouseConstants.QUERY;
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

/**
 * @author Ivica Cardic
 */
public class ClickHouseExecuteQueryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("executeQuery")
        .title("Execute Query")
        .description("Executes a SQL query and returns the result as JSON.")
        .properties(
            string(QUERY)
                .label("Query")
                .description("The SQL query to execute (e.g. SELECT * FROM system.tables LIMIT 10).")
                .controlType(ControlType.TEXT_AREA)
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("meta")
                            .description("The metadata of the result columns."),
                        object("data")
                            .description("The rows of the result."),
                        string("rows")
                            .description("The number of rows in the result."))))
        .help("", "https://docs.bytechef.io/reference/components/clickHouse_v1#execute-query")
        .perform(ClickHouseExecuteQueryAction::perform);

    private ClickHouseExecuteQueryAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String query = inputParameters.getRequiredString(QUERY);

        return context.http(http -> http.post("/"))
            .queryParameters("default_format", "JSON")
            .body(Body.of(query, "text/plain"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

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

package com.bytechef.component.cratedb.action;

import static com.bytechef.component.cratedb.constant.CrateDbConstants.STATEMENT;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
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
import com.bytechef.component.definition.Property.ControlType;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class CrateDbExecuteQueryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("executeQuery")
        .title("Execute Query")
        .description("Executes a SQL statement and returns the result.")
        .properties(
            string(STATEMENT)
                .label("Statement")
                .description("The SQL statement to execute (e.g. SELECT * FROM sys.cluster).")
                .controlType(ControlType.TEXT_AREA)
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("cols")
                            .description("The column names of the result.")
                            .items(string()),
                        array("rows")
                            .description("The rows of the result."),
                        integer("rowcount")
                            .description("The number of rows in the result."))))
        .help("", "https://docs.bytechef.io/reference/components/crateDb_v1#execute-query")
        .perform(CrateDbExecuteQueryAction::perform);

    private CrateDbExecuteQueryAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/_sql"))
            .body(Body.of(Map.of("stmt", inputParameters.getRequiredString(STATEMENT))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

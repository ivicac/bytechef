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

package com.bytechef.component.neo4j.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.neo4j.constant.Neo4jConstants.DATABASE;
import static com.bytechef.component.neo4j.constant.Neo4jConstants.STATEMENT;

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
public class Neo4jExecuteQueryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("executeQuery")
        .title("Execute Query")
        .description("Executes a Cypher query against the database.")
        .properties(
            string(DATABASE)
                .label("Database")
                .description("The name of the Neo4j database.")
                .defaultValue("neo4j")
                .required(true),
            string(STATEMENT)
                .label("Statement")
                .description("The Cypher statement to execute (e.g. MATCH (n) RETURN n LIMIT 10).")
                .controlType(ControlType.TEXT_AREA)
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        object("data")
                            .description("The result of the query with fields and values."))))
        .help("", "https://docs.bytechef.io/reference/components/neo4j_v1#execute-query")
        .perform(Neo4jExecuteQueryAction::perform);

    private Neo4jExecuteQueryAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.post("/db/%s/query/v2".formatted(inputParameters.getRequiredString(DATABASE))))
            .body(Body.of(Map.of(STATEMENT, inputParameters.getRequiredString(STATEMENT))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

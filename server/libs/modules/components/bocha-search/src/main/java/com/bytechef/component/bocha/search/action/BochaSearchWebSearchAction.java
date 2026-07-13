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

package com.bytechef.component.bocha.search.action;

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
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class BochaSearchWebSearchAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("webSearch")
        .title("Web Search")
        .description("Searches the web and returns relevant results.")
        .properties(
            string("query")
                .label("Query")
                .description("The search query.")
                .required(true),
            integer("count")
                .label("Count")
                .description("The number of results to return.")
                .defaultValue(10)
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        integer("code"),
                        object("data")
                            .properties(
                                object("webPages")
                                    .properties(
                                        array("value")
                                            .items(
                                                object()
                                                    .properties(
                                                        string("name"),
                                                        string("url"),
                                                        string("snippet"))))))))
        .perform(BochaSearchWebSearchAction::perform);

    private BochaSearchWebSearchAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/web-search"))
            .body(
                Body.of(
                    Map.of(
                        "query", inputParameters.getRequiredString("query"),
                        "count", inputParameters.getInteger("count", 10),
                        "summary", true)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

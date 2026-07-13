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

package com.bytechef.component.mem0.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.mem0.constant.Mem0Constants.USER_ID;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class Mem0AddMemoryAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("addMemory")
        .title("Add Memory")
        .description("Stores a memory for the user.")
        .properties(
            string("text")
                .label("Text")
                .description("The text to remember.")
                .required(true),
            string(USER_ID)
                .label("User Id")
                .description("The id of the user the memory belongs to.")
                .required(true))
        .output(
            outputSchema(
                array()
                    .description("The results of the add operation.")
                    .items(
                        object()
                            .properties(
                                string("id")
                                    .description("The id of the memory."),
                                string("event")
                                    .description("The event type of the result.")))))
        .help("", "https://docs.bytechef.io/reference/components/mem0_v1#add-memory")
        .perform(Mem0AddMemoryAction::perform);

    private Mem0AddMemoryAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/memories/add/"))
            .body(
                Body.of(
                    Map.of(
                        "messages",
                        List.of(Map.of("role", "user", "content", inputParameters.getRequiredString("text"))),
                        "user_id", inputParameters.getRequiredString(USER_ID))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

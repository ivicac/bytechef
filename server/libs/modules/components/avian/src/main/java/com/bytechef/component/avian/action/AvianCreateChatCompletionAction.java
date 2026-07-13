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

package com.bytechef.component.avian.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
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
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class AvianCreateChatCompletionAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createChatCompletion")
        .title("Create Chat Completion")
        .description("Generates a chat completion using an open-source model hosted on Avian.")
        .properties(
            string("model")
                .label("Model")
                .description("The model to use for the completion.")
                .defaultValue("Meta-Llama-3.1-405B-Instruct")
                .required(true),
            string("prompt")
                .label("Prompt")
                .description("The user message to send to the model.")
                .controlType(ControlType.TEXT_AREA)
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id"),
                        array("choices")
                            .items(
                                object()
                                    .properties(
                                        object("message")
                                            .properties(
                                                string("role"),
                                                string("content")))))))
        .perform(AvianCreateChatCompletionAction::perform);

    private AvianCreateChatCompletionAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/chat/completions"))
            .body(
                Body.of(
                    Map.of(
                        "model", inputParameters.getRequiredString("model"),
                        "messages", List.of(
                            Map.of(
                                "role", "user",
                                "content", inputParameters.getRequiredString("prompt"))))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

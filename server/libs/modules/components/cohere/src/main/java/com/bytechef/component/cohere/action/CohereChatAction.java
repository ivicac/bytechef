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

package com.bytechef.component.cohere.action;

import static com.bytechef.component.cohere.constant.CohereConstants.MESSAGE;
import static com.bytechef.component.cohere.constant.CohereConstants.MODEL;
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
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class CohereChatAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("chat")
        .title("Chat")
        .description("Generates a chat response for the message.")
        .properties(
            string(MODEL)
                .label("Model")
                .description("The name of the Cohere model to use (e.g. command-r-plus).")
                .defaultValue("command-r-plus")
                .required(true),
            string(MESSAGE)
                .label("Message")
                .description("The message to send to the model.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the response."),
                        object("message")
                            .description("The response message.")
                            .properties(
                                array("content")
                                    .description("The content blocks of the response.")
                                    .items(
                                        object()
                                            .properties(
                                                string("text")
                                                    .description("The generated text.")))))))
        .help("", "https://docs.bytechef.io/reference/components/cohere_v1#chat")
        .perform(CohereChatAction::perform);

    private CohereChatAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/chat"))
            .body(
                Body.of(
                    Map.of(
                        MODEL, inputParameters.getRequiredString(MODEL),
                        "messages",
                        List.of(Map.of("role", "user", "content", inputParameters.getRequiredString(MESSAGE))))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

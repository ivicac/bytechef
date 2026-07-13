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

package com.bytechef.component.mistral.ai.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.mistral.ai.constant.MistralAiConstants.MESSAGES;
import static com.bytechef.component.mistral.ai.constant.MistralAiConstants.MODEL;
import static com.bytechef.component.mistral.ai.constant.MistralAiConstants.PROMPT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class MistralAiCreateChatCompletionAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createChatCompletion")
        .title("Create Chat Completion")
        .description("Creates a chat completion using a Mistral AI model.")
        .properties(
            string(MODEL)
                .label("Model")
                .description("The id of the model to use, e.g. mistral-small-latest.")
                .defaultValue("mistral-small-latest")
                .required(true),
            string(PROMPT)
                .label("Prompt")
                .description("The user message the completion is generated for.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the chat completion."),
                        string("model")
                            .description("The model used for the chat completion."),
                        array("choices")
                            .description("The generated completions.")
                            .items(
                                object()
                                    .properties(
                                        object("message")
                                            .properties(
                                                string("role")
                                                    .description("The role of the message author."),
                                                string("content")
                                                    .description("The content of the message.")))))))
        .help("", "https://docs.bytechef.io/reference/components/mistralAi_v1#create-chat-completion")
        .perform(MistralAiCreateChatCompletionAction::perform);

    private MistralAiCreateChatCompletionAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/chat/completions"))
            .body(
                Body.of(
                    MODEL, inputParameters.getRequiredString(MODEL),
                    MESSAGES, List.of(
                        Map.of(
                            "role", "user",
                            "content", inputParameters.getRequiredString(PROMPT)))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

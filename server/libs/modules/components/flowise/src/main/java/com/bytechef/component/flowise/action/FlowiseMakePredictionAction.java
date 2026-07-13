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

package com.bytechef.component.flowise.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.flowise.constant.FlowiseConstants.CHATFLOW_ID;
import static com.bytechef.component.flowise.constant.FlowiseConstants.QUESTION;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class FlowiseMakePredictionAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("makePrediction")
        .title("Make Prediction")
        .description("Sends a question to a Flowise chatflow and returns the answer.")
        .properties(
            string(CHATFLOW_ID)
                .label("Chatflow Id")
                .description("The id of the Flowise chatflow.")
                .required(true),
            string(QUESTION)
                .label("Question")
                .description("The question to send to the chatflow.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("text")
                            .description("The answer of the chatflow."),
                        string("chatId")
                            .description("The id of the chat session."))))
        .help("", "https://docs.bytechef.io/reference/components/flowise_v1#make-prediction")
        .perform(FlowiseMakePredictionAction::perform);

    private FlowiseMakePredictionAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context
            .http(http -> http.post("/prediction/" + inputParameters.getRequiredString(CHATFLOW_ID)))
            .body(Body.of(Map.of(QUESTION, inputParameters.getRequiredString(QUESTION))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

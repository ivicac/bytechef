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

package com.bytechef.component.retell.ai.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.retell.ai.constant.RetellAiConstants.FROM_NUMBER;
import static com.bytechef.component.retell.ai.constant.RetellAiConstants.TO_NUMBER;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class RetellAiCreatePhoneCallAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createPhoneCall")
        .title("Create Phone Call")
        .description("Creates an outbound phone call handled by the AI agent bound to the from number.")
        .properties(
            string(FROM_NUMBER)
                .label("From Number")
                .description("The number the call will be made from, in E.164 format (e.g. +14157774444).")
                .required(true),
            string(TO_NUMBER)
                .label("To Number")
                .description("The number that will be called, in E.164 format (e.g. +12137774445).")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("call_id")
                            .description("The id of the created call."),
                        string("agent_id")
                            .description("The id of the agent handling the call."),
                        string("call_status")
                            .description("The status of the call."),
                        string("from_number")
                            .description("The number the call is made from."),
                        string("to_number")
                            .description("The number being called."))))
        .help("", "https://docs.bytechef.io/reference/components/retellAi_v1#create-phone-call")
        .perform(RetellAiCreatePhoneCallAction::perform);

    private RetellAiCreatePhoneCallAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context.http(http -> http.post("/v2/create-phone-call"))
            .body(
                Body.of(
                    FROM_NUMBER, inputParameters.getRequiredString(FROM_NUMBER),
                    TO_NUMBER, inputParameters.getRequiredString(TO_NUMBER)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

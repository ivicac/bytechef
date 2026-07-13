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

package com.bytechef.component.plivo.action;

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
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class PlivoSendSmsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendSms")
        .title("Send SMS")
        .description("Sends an SMS message.")
        .properties(
            string("src")
                .label("From")
                .description("The sending phone number in E.164 format. Must be a Plivo number on your account.")
                .required(true),
            string("dst")
                .label("To")
                .description("The receiving phone number in E.164 format.")
                .required(true),
            string("text")
                .label("Text")
                .description("The text of the message.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("message"),
                        array("message_uuid")
                            .items(string()),
                        string("api_id"))))
        .perform(PlivoSendSmsAction::perform);

    private PlivoSendSmsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/Message/"))
            .body(
                Body.of(
                    Map.of(
                        "src", inputParameters.getRequiredString("src"),
                        "dst", inputParameters.getRequiredString("dst"),
                        "text", inputParameters.getRequiredString("text"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

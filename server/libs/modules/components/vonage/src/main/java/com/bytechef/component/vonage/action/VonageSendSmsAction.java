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

package com.bytechef.component.vonage.action;

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
public class VonageSendSmsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendSms")
        .title("Send SMS")
        .description("Sends an SMS message.")
        .properties(
            string("from")
                .label("From")
                .description("The sender ID or phone number the message is sent from.")
                .required(true),
            string("to")
                .label("To")
                .description("The receiving phone number in E.164 format without a leading plus sign.")
                .required(true),
            string("text")
                .label("Text")
                .description("The text of the message.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("message-count"),
                        array("messages")
                            .items(
                                object()
                                    .properties(
                                        string("to"),
                                        string("message-id"),
                                        string("status"))))))
        .perform(VonageSendSmsAction::perform);

    private VonageSendSmsAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/sms/json"))
            .body(
                Body.of(
                    Map.of(
                        "from", inputParameters.getRequiredString("from"),
                        "to", inputParameters.getRequiredString("to"),
                        "text", inputParameters.getRequiredString("text"))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

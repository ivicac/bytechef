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

package com.bytechef.component.messagebird.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.messagebird.constant.MessageBirdConstants.BODY;
import static com.bytechef.component.messagebird.constant.MessageBirdConstants.ORIGINATOR;
import static com.bytechef.component.messagebird.constant.MessageBirdConstants.RECIPIENTS;

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
public class MessageBirdSendSmsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendSms")
        .title("Send SMS")
        .description("Sends an SMS message.")
        .properties(
            string(ORIGINATOR)
                .label("Originator")
                .description("The sender of the message, a phone number or an alphanumeric string.")
                .required(true),
            string(RECIPIENTS)
                .label("Recipient")
                .description("The phone number of the recipient in international format.")
                .required(true),
            string(BODY)
                .label("Body")
                .description("The text of the message.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the sent message."),
                        string("originator")
                            .description("The sender of the message."),
                        string("body")
                            .description("The text of the message."))))
        .help("", "https://docs.bytechef.io/reference/components/messageBird_v1#send-sms")
        .perform(MessageBirdSendSmsAction::perform);

    private MessageBirdSendSmsAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/messages"))
            .body(
                Body.of(
                    ORIGINATOR, inputParameters.getRequiredString(ORIGINATOR),
                    RECIPIENTS, List.of(
                        inputParameters.getRequiredString(RECIPIENTS)
                            .split(",")),
                    BODY, inputParameters.getRequiredString(BODY)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

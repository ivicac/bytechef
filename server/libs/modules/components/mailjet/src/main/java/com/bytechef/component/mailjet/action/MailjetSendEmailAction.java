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

package com.bytechef.component.mailjet.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.mailjet.constant.MailjetConstants.FROM_EMAIL;
import static com.bytechef.component.mailjet.constant.MailjetConstants.SUBJECT;
import static com.bytechef.component.mailjet.constant.MailjetConstants.TEXT;
import static com.bytechef.component.mailjet.constant.MailjetConstants.TO_EMAIL;

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
public class MailjetSendEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendEmail")
        .title("Send Email")
        .description("Sends an email.")
        .properties(
            string(FROM_EMAIL)
                .label("From Email")
                .description("The email address of the sender, validated in Mailjet.")
                .required(true),
            string(TO_EMAIL)
                .label("To Email")
                .description("The email address of the recipient.")
                .required(true),
            string(SUBJECT)
                .label("Subject")
                .description("The subject of the email.")
                .required(true),
            string(TEXT)
                .label("Text")
                .description("The plain text content of the email.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("Messages")
                            .description("The results of the sent messages.")
                            .items(
                                object()
                                    .properties(
                                        string("Status")
                                            .description("The status of the sent message."))))))
        .help("", "https://docs.bytechef.io/reference/components/mailjet_v1#send-email")
        .perform(MailjetSendEmailAction::perform);

    private MailjetSendEmailAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context
            .http(http -> http.post("/v3.1/send"))
            .body(
                Body.of(
                    Map.of(
                        "Messages", List.of(
                            Map.of(
                                "From", Map.of("Email", inputParameters.getRequiredString(FROM_EMAIL)),
                                "To", List.of(Map.of("Email", inputParameters.getRequiredString(TO_EMAIL))),
                                "Subject", inputParameters.getRequiredString(SUBJECT),
                                "TextPart", inputParameters.getRequiredString(TEXT))))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

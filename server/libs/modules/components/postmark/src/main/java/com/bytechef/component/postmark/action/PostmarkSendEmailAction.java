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

package com.bytechef.component.postmark.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.postmark.constant.PostmarkConstants.FROM;
import static com.bytechef.component.postmark.constant.PostmarkConstants.HTML_BODY;
import static com.bytechef.component.postmark.constant.PostmarkConstants.SUBJECT;
import static com.bytechef.component.postmark.constant.PostmarkConstants.TEXT_BODY;
import static com.bytechef.component.postmark.constant.PostmarkConstants.TO;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class PostmarkSendEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendEmail")
        .title("Send Email")
        .description("Sends a single email via Postmark.")
        .properties(
            string(FROM)
                .label("From")
                .description("The email address the email will be sent from. Must be a verified sender signature.")
                .required(true),
            string(TO)
                .label("To")
                .description("The email address the email will be sent to. Separate multiple addresses with commas.")
                .required(true),
            string(SUBJECT)
                .label("Subject")
                .description("The subject of the email.")
                .required(true),
            string(TEXT_BODY)
                .label("Text Body")
                .description("The plain-text body of the email.")
                .required(false),
            string(HTML_BODY)
                .label("HTML Body")
                .description("The HTML body of the email.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("MessageID")
                            .description("The id of the sent message."),
                        string("To")
                            .description("The recipient of the sent message."),
                        string("SubmittedAt")
                            .description("The date and time the message was submitted."),
                        integer("ErrorCode")
                            .description("The error code of the request."),
                        string("Message")
                            .description("The status message of the request."))))
        .help("", "https://docs.bytechef.io/reference/components/postmark_v1#send-email")
        .perform(PostmarkSendEmailAction::perform);

    private PostmarkSendEmailAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(FROM, inputParameters.getRequiredString(FROM));
        body.put(TO, inputParameters.getRequiredString(TO));
        body.put(SUBJECT, inputParameters.getRequiredString(SUBJECT));

        String textBody = inputParameters.getString(TEXT_BODY);

        if (textBody != null) {
            body.put(TEXT_BODY, textBody);
        }

        String htmlBody = inputParameters.getString(HTML_BODY);

        if (htmlBody != null) {
            body.put(HTML_BODY, htmlBody);
        }

        return context.http(http -> http.post("/email"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

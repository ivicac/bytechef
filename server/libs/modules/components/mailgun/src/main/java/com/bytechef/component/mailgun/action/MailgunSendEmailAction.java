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

package com.bytechef.component.mailgun.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.mailgun.constant.MailgunConstants.DOMAIN;
import static com.bytechef.component.mailgun.constant.MailgunConstants.FROM;
import static com.bytechef.component.mailgun.constant.MailgunConstants.HTML;
import static com.bytechef.component.mailgun.constant.MailgunConstants.SUBJECT;
import static com.bytechef.component.mailgun.constant.MailgunConstants.TEXT;
import static com.bytechef.component.mailgun.constant.MailgunConstants.TO;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.BodyContentType;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class MailgunSendEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendEmail")
        .title("Send Email")
        .description("Sends an email via the Mailgun messages API.")
        .properties(
            string(DOMAIN)
                .label("Domain")
                .description("The sending domain registered in Mailgun (e.g. mg.example.com).")
                .required(true),
            string(FROM)
                .label("From")
                .description("The email address of the sender (e.g. Excited User <mailgun@mg.example.com>).")
                .required(true),
            string(TO)
                .label("To")
                .description("Comma-separated email addresses of the recipients.")
                .required(true),
            string(SUBJECT)
                .label("Subject")
                .description("The subject of the email.")
                .required(true),
            string(TEXT)
                .label("Text")
                .description("The plain text content of the email.")
                .required(false),
            string(HTML)
                .label("HTML")
                .description("The HTML content of the email.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the sent message."),
                        string("message")
                            .description("The status message."))))
        .help("", "https://docs.bytechef.io/reference/components/mailgun_v1#send-email")
        .perform(MailgunSendEmailAction::perform);

    private MailgunSendEmailAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put(FROM, inputParameters.getRequiredString(FROM));
        body.put(TO, inputParameters.getRequiredString(TO));
        body.put(SUBJECT, inputParameters.getRequiredString(SUBJECT));

        String text = inputParameters.getString(TEXT);

        if (text != null) {
            body.put(TEXT, text);
        }

        String html = inputParameters.getString(HTML);

        if (html != null) {
            body.put(HTML, html);
        }

        return context
            .http(http -> http.post("/%s/messages".formatted(inputParameters.getRequiredString(DOMAIN))))
            .body(Body.of(body, BodyContentType.FORM_URL_ENCODED))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

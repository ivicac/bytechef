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

package com.bytechef.component.mandrill.action;

import static com.bytechef.component.definition.Authorization.KEY;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.option;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class MandrillSendEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendEmail")
        .title("Send Email")
        .description("Sends a transactional email.")
        .properties(
            string("fromEmail")
                .label("From Email")
                .description("The email address of the sender.")
                .required(true),
            string("fromName")
                .label("From Name")
                .description("The name of the sender.")
                .required(false),
            array("to")
                .label("To")
                .description("The email addresses of the recipients.")
                .items(string())
                .required(true),
            string("subject")
                .label("Subject")
                .description("The subject of the email.")
                .required(true),
            string("body")
                .label("Body")
                .description("The content of the email.")
                .required(true),
            string("contentType")
                .label("Content Type")
                .description("The content type of the email body.")
                .options(
                    option("HTML", "html"),
                    option("Plain Text", "text"))
                .defaultValue("html")
                .required(false))
        .output(
            outputSchema(
                array()
                    .items(
                        object()
                            .properties(
                                string("email"),
                                string("status"),
                                string("_id")))))
        .perform(MandrillSendEmailAction::perform);

    private MandrillSendEmailAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> message = new HashMap<>();

        message.put("from_email", inputParameters.getRequiredString("fromEmail"));
        message.put("subject", inputParameters.getRequiredString("subject"));
        message.put(
            "to",
            inputParameters.getRequiredList("to", String.class)
                .stream()
                .map(email -> Map.of("email", email))
                .toList());

        if (inputParameters.getString("fromName") != null) {
            message.put("from_name", inputParameters.getString("fromName"));
        }

        String contentType = inputParameters.getString("contentType", "html");

        message.put("html".equals(contentType) ? "html" : "text", inputParameters.getRequiredString("body"));

        return context.http(http -> http.post("/messages/send.json"))
            .body(
                Body.of(
                    Map.of(
                        "key", connectionParameters.getRequiredString(KEY),
                        "message", message)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

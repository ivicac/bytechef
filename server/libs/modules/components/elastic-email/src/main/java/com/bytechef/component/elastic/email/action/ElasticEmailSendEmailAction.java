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

package com.bytechef.component.elastic.email.action;

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
import java.util.List;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class ElasticEmailSendEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendEmail")
        .title("Send Email")
        .description("Sends a transactional email.")
        .properties(
            array("to")
                .label("To")
                .description("The email addresses of the recipients.")
                .items(string())
                .required(true),
            string("from")
                .label("From")
                .description("The email address of the sender. Must be a verified sender in Elastic Email.")
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
                    option("HTML", "HTML"),
                    option("Plain Text", "PlainText"))
                .defaultValue("HTML")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("TransactionID"),
                        string("MessageID"))))
        .perform(ElasticEmailSendEmailAction::perform);

    private ElasticEmailSendEmailAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        return context.http(http -> http.post("/emails/transactional"))
            .body(
                Body.of(
                    Map.of(
                        "Recipients", Map.of("To", inputParameters.getRequiredList("to", String.class)),
                        "Content", Map.of(
                            "From", inputParameters.getRequiredString("from"),
                            "Subject", inputParameters.getRequiredString("subject"),
                            "Body", List.of(
                                Map.of(
                                    "ContentType", inputParameters.getString("contentType", "HTML"),
                                    "Content", inputParameters.getRequiredString("body"),
                                    "Charset", "utf-8"))))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

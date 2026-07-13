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

package com.bytechef.component.aws.ses.action;

import static com.bytechef.component.aws.ses.constant.AwsSesConstants.BODY;
import static com.bytechef.component.aws.ses.constant.AwsSesConstants.FROM;
import static com.bytechef.component.aws.ses.constant.AwsSesConstants.SUBJECT;
import static com.bytechef.component.aws.ses.constant.AwsSesConstants.TO;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.aws.ses.util.AwsSesUtils;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * @author Ivica Cardic
 */
public class AwsSesSendEmailAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendEmail")
        .title("Send Email")
        .description("Sends a plain-text email via Amazon SES.")
        .properties(
            string(FROM)
                .label("From")
                .description("The email address the email will be sent from. Must be verified in SES.")
                .required(true),
            array(TO)
                .label("To")
                .description("The email addresses the email will be sent to.")
                .items(string())
                .required(true),
            string(SUBJECT)
                .label("Subject")
                .description("The subject of the email.")
                .required(true),
            string(BODY)
                .label("Body")
                .description("The plain-text body of the email.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("messageId")
                            .description("The id of the sent message."))))
        .help("", "https://docs.bytechef.io/reference/components/awsSes_v1#send-email")
        .perform(AwsSesSendEmailAction::perform);

    private AwsSesSendEmailAction() {
    }

    public static Map<String, String> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        List<String> toAddresses = inputParameters.getRequiredList(TO, String.class);

        try (SesClient sesClient = AwsSesUtils.buildSesClient(connectionParameters)) {
            SendEmailResponse sendEmailResponse = sesClient.sendEmail(
                SendEmailRequest.builder()
                    .source(inputParameters.getRequiredString(FROM))
                    .destination(Destination.builder()
                        .toAddresses(toAddresses)
                        .build())
                    .message(Message.builder()
                        .subject(Content.builder()
                            .data(inputParameters.getRequiredString(SUBJECT))
                            .build())
                        .body(Body.builder()
                            .text(Content.builder()
                                .data(inputParameters.getRequiredString(BODY))
                                .build())
                            .build())
                        .build())
                    .build());

            return Map.of("messageId", sendEmailResponse.messageId());
        }
    }
}

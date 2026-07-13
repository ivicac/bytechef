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

package com.bytechef.component.aws.sqs.action;

import static com.bytechef.component.aws.sqs.constant.AwsSqsConstants.MESSAGE_BODY;
import static com.bytechef.component.aws.sqs.constant.AwsSqsConstants.QUEUE_URL;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;

import com.bytechef.component.aws.sqs.util.AwsSqsUtils;
import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Parameters;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * @author Ivica Cardic
 */
public class AwsSqsSendMessageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendMessage")
        .title("Send Message")
        .description("Sends a message to a queue.")
        .properties(
            string(QUEUE_URL)
                .label("Queue URL")
                .description("The URL of the queue the message is sent to.")
                .required(true),
            string(MESSAGE_BODY)
                .label("Message Body")
                .description("The body of the message.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("messageId")
                            .description("The id of the sent message."),
                        string("md5OfMessageBody")
                            .description("The MD5 digest of the message body."))))
        .help("", "https://docs.bytechef.io/reference/components/awsSqs_v1#send-message")
        .perform(AwsSqsSendMessageAction::perform);

    private AwsSqsSendMessageAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        try (SqsClient sqsClient = AwsSqsUtils.buildSqsClient(connectionParameters)) {
            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                .queueUrl(inputParameters.getRequiredString(QUEUE_URL))
                .messageBody(inputParameters.getRequiredString(MESSAGE_BODY))
                .build();

            SendMessageResponse sendMessageResponse = sqsClient.sendMessage(sendMessageRequest);

            Map<String, Object> result = new HashMap<>();

            result.put("messageId", sendMessageResponse.messageId());
            result.put("md5OfMessageBody", sendMessageResponse.md5OfMessageBody());

            return result;
        }
    }
}

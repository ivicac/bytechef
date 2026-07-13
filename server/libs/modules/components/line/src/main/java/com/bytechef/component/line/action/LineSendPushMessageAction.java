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

package com.bytechef.component.line.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.line.constant.LineConstants.MESSAGES;
import static com.bytechef.component.line.constant.LineConstants.TEXT;
import static com.bytechef.component.line.constant.LineConstants.TO;

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
public class LineSendPushMessageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendPushMessage")
        .title("Send Push Message")
        .description("Sends a text message to a user, group chat or multi-person chat.")
        .properties(
            string(TO)
                .label("To")
                .description("The id of the user, group chat or multi-person chat that receives the message.")
                .required(true),
            string(TEXT)
                .label("Message Text")
                .description("The text of the message that will be sent.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("sentMessages")
                            .description("The sent messages.")
                            .items(
                                object()
                                    .properties(
                                        string("id")
                                            .description("The id of the sent message."),
                                        string("quoteToken")
                                            .description("The quote token of the sent message."))))))
        .help("", "https://docs.bytechef.io/reference/components/line_v1#send-push-message")
        .perform(LineSendPushMessageAction::perform);

    private LineSendPushMessageAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context.http(http -> http.post("/message/push"))
            .body(
                Body.of(
                    TO, inputParameters.getRequiredString(TO),
                    MESSAGES, List.of(
                        Map.of(
                            "type", "text",
                            TEXT, inputParameters.getRequiredString(TEXT)))))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

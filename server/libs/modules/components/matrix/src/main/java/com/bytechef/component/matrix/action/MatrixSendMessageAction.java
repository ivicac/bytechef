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

package com.bytechef.component.matrix.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.matrix.constant.MatrixConstants.ROOM_ID;
import static com.bytechef.component.matrix.constant.MatrixConstants.TEXT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;
import java.util.UUID;

/**
 * @author Ivica Cardic
 */
public class MatrixSendMessageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendMessage")
        .title("Send Message")
        .description("Sends a text message to a room.")
        .properties(
            string(ROOM_ID)
                .label("Room ID")
                .description("The id of the room the message is sent to.")
                .required(true),
            string(TEXT)
                .label("Text")
                .description("The text of the message.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("event_id")
                            .description("The id of the sent message event."))))
        .help("", "https://docs.bytechef.io/reference/components/matrix_v1#send-message")
        .perform(MatrixSendMessageAction::perform);

    private MatrixSendMessageAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        UUID transactionId = UUID.randomUUID();

        return context
            .http(http -> http.put(
                "/rooms/%s/send/m.room.message/%s".formatted(
                    inputParameters.getRequiredString(ROOM_ID), transactionId)))
            .body(
                Body.of(
                    "msgtype", "m.text",
                    "body", inputParameters.getRequiredString(TEXT)))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

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

package com.bytechef.component.webex.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
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
public class WebexSendMessageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendMessage")
        .title("Send Message")
        .description("Sends a message to a room or a person.")
        .properties(
            string("roomId")
                .label("Room ID")
                .description("The ID of the room to send the message to. Leave empty when sending to a person.")
                .required(false),
            string("toPersonEmail")
                .label("To Person Email")
                .description("The email of the person to send a direct message to. Leave empty when using a room.")
                .required(false),
            string("text")
                .label("Text")
                .description("The plain text content of the message.")
                .required(true))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id"),
                        string("roomId"),
                        string("text"),
                        string("created"))))
        .perform(WebexSendMessageAction::perform);

    private WebexSendMessageAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("text", inputParameters.getRequiredString("text"));

        if (inputParameters.getString("roomId") != null) {
            body.put("roomId", inputParameters.getString("roomId"));
        }

        if (inputParameters.getString("toPersonEmail") != null) {
            body.put("toPersonEmail", inputParameters.getString("toPersonEmail"));
        }

        return context.http(http -> http.post("/messages"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

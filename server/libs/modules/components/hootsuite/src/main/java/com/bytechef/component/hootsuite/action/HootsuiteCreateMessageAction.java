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

package com.bytechef.component.hootsuite.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
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
public class HootsuiteCreateMessageAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("createMessage")
        .title("Create Message")
        .description("Creates and schedules a message on one or more social profiles.")
        .properties(
            string("text")
                .label("Text")
                .description("The text content of the message.")
                .required(true),
            array("socialProfileIds")
                .label("Social Profile IDs")
                .description("The IDs of the social profiles to publish the message to.")
                .items(string())
                .required(true),
            string("scheduledSendTime")
                .label("Scheduled Send Time")
                .description("The time to send the message in ISO-8601 format (UTC). Leave empty to send now.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        array("data")
                            .items(
                                object()
                                    .properties(
                                        string("id"),
                                        string("state"),
                                        string("scheduledSendTime"))))))
        .perform(HootsuiteCreateMessageAction::perform);

    private HootsuiteCreateMessageAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put("text", inputParameters.getRequiredString("text"));
        body.put("socialProfileIds", inputParameters.getRequiredList("socialProfileIds", String.class));

        if (inputParameters.getString("scheduledSendTime") != null) {
            body.put("scheduledSendTime", inputParameters.getString("scheduledSendTime"));
        }

        return context.http(http -> http.post("/messages"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

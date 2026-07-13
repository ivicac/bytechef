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

package com.bytechef.component.vero.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.vero.constant.VeroConstants.AUTH_TOKEN;
import static com.bytechef.component.vero.constant.VeroConstants.DATA;
import static com.bytechef.component.vero.constant.VeroConstants.EMAIL;
import static com.bytechef.component.vero.constant.VeroConstants.EVENT_NAME;
import static com.bytechef.component.vero.constant.VeroConstants.USER_ID;

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
public class VeroTrackEventAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("trackEvent")
        .title("Track Event")
        .description("Tracks an event for a user.")
        .properties(
            string(USER_ID)
                .label("User ID")
                .description("The id of the user the event is tracked for.")
                .required(true),
            string(EMAIL)
                .label("Email")
                .description("The email address of the user.")
                .required(false),
            string(EVENT_NAME)
                .label("Event Name")
                .description("The name of the event.")
                .required(true),
            object(DATA)
                .label("Data")
                .description("The data of the event.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("message")
                            .description("The result message of the request."))))
        .help("", "https://docs.bytechef.io/reference/components/vero_v1#track-event")
        .perform(VeroTrackEventAction::perform);

    private VeroTrackEventAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> identity = new HashMap<>();

        identity.put("id", inputParameters.getRequiredString(USER_ID));

        String email = inputParameters.getString(EMAIL);

        if (email != null) {
            identity.put(EMAIL, email);
        }

        Map<String, Object> body = new HashMap<>();

        body.put("auth_token", connectionParameters.getRequiredString(AUTH_TOKEN));
        body.put("identity", identity);
        body.put("event_name", inputParameters.getRequiredString(EVENT_NAME));

        Map<String, Object> data = inputParameters.getMap(DATA, Object.class, Map.of());

        if (!data.isEmpty()) {
            body.put(DATA, data);
        }

        return context
            .http(http -> http.post("/events/track"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

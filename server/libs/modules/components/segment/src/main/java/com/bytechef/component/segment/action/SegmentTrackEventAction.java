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

package com.bytechef.component.segment.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.segment.constant.SegmentConstants.EVENT;
import static com.bytechef.component.segment.constant.SegmentConstants.PROPERTIES;
import static com.bytechef.component.segment.constant.SegmentConstants.USER_ID;

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
public class SegmentTrackEventAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("trackEvent")
        .title("Track Event")
        .description("Records an event a user has performed.")
        .properties(
            string(USER_ID)
                .label("User ID")
                .description("The id of the user the event is attributed to.")
                .required(true),
            string(EVENT)
                .label("Event")
                .description("The name of the event.")
                .required(true),
            object(PROPERTIES)
                .label("Properties")
                .description("The properties of the event.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("success")
                            .description("Whether the event was accepted."))))
        .help("", "https://docs.bytechef.io/reference/components/segment_v1#track-event")
        .perform(SegmentTrackEventAction::perform);

    private SegmentTrackEventAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put(USER_ID, inputParameters.getRequiredString(USER_ID));
        body.put(EVENT, inputParameters.getRequiredString(EVENT));

        Map<String, Object> properties = inputParameters.getMap(PROPERTIES, Object.class, Map.of());

        if (!properties.isEmpty()) {
            body.put(PROPERTIES, properties);
        }

        return context
            .http(http -> http.post("/track"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

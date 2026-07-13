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

package com.bytechef.component.splunk.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.splunk.constant.SplunkConstants.EVENT;
import static com.bytechef.component.splunk.constant.SplunkConstants.SOURCE;
import static com.bytechef.component.splunk.constant.SplunkConstants.SOURCETYPE;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class SplunkSendEventAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("sendEvent")
        .title("Send Event")
        .description("Sends an event to the Splunk HTTP Event Collector.")
        .properties(
            string(EVENT)
                .label("Event")
                .description("The event data to send.")
                .controlType(ControlType.TEXT_AREA)
                .required(true),
            string(SOURCE)
                .label("Source")
                .description("The source of the event.")
                .required(false),
            string(SOURCETYPE)
                .label("Source Type")
                .description("The source type of the event.")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("text")
                            .description("The status message."),
                        integer("code")
                            .description("The status code."))))
        .help("", "https://docs.bytechef.io/reference/components/splunk_v1#send-event")
        .perform(SplunkSendEventAction::perform);

    private SplunkSendEventAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        Map<String, Object> body = new HashMap<>();

        body.put(EVENT, inputParameters.getRequiredString(EVENT));

        String source = inputParameters.getString(SOURCE);

        if (source != null) {
            body.put(SOURCE, source);
        }

        String sourcetype = inputParameters.getString(SOURCETYPE);

        if (sourcetype != null) {
            body.put(SOURCETYPE, sourcetype);
        }

        return context.http(http -> http.post("/services/collector/event"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

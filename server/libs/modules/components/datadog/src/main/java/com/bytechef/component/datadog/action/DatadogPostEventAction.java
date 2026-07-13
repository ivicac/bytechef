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

package com.bytechef.component.datadog.action;

import static com.bytechef.component.datadog.constant.DatadogConstants.ALERT_TYPE;
import static com.bytechef.component.datadog.constant.DatadogConstants.TEXT;
import static com.bytechef.component.datadog.constant.DatadogConstants.TITLE;
import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.integer;
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
import com.bytechef.component.definition.TypeReference;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class DatadogPostEventAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("postEvent")
        .title("Post Event")
        .description("Posts an event to the Datadog event stream.")
        .properties(
            string(TITLE)
                .label("Title")
                .description("The title of the event.")
                .required(true),
            string(TEXT)
                .label("Text")
                .description("The body text of the event.")
                .required(true),
            string(ALERT_TYPE)
                .label("Alert Type")
                .description("The alert type of the event.")
                .options(
                    option("Info", "info"),
                    option("Success", "success"),
                    option("Warning", "warning"),
                    option("Error", "error"))
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("status")
                            .description("The status of the request."),
                        object("event")
                            .description("The created event.")
                            .properties(
                                integer("id")
                                    .description("The id of the created event."),
                                string("title")
                                    .description("The title of the created event."),
                                string("url")
                                    .description("The URL of the created event.")))))
        .help("", "https://docs.bytechef.io/reference/components/datadog_v1#post-event")
        .perform(DatadogPostEventAction::perform);

    private DatadogPostEventAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        Map<String, Object> body = new HashMap<>();

        body.put(TITLE, inputParameters.getRequiredString(TITLE));
        body.put(TEXT, inputParameters.getRequiredString(TEXT));

        String alertType = inputParameters.getString(ALERT_TYPE);

        if (alertType != null) {
            body.put(ALERT_TYPE, alertType);
        }

        return context.http(http -> http.post("/api/v1/events"))
            .body(Body.of(body))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}

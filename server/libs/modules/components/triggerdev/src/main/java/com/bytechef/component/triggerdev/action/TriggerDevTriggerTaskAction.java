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

package com.bytechef.component.triggerdev.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.triggerdev.constant.TriggerDevConstants.PAYLOAD;
import static com.bytechef.component.triggerdev.constant.TriggerDevConstants.TASK_IDENTIFIER;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.Property.ControlType;

/**
 * @author Ivica Cardic
 */
public class TriggerDevTriggerTaskAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("triggerTask")
        .title("Trigger Task")
        .description("Triggers a run of the task with the given payload.")
        .properties(
            string(TASK_IDENTIFIER)
                .label("Task Identifier")
                .description("The identifier of the Trigger.dev task.")
                .required(true),
            string(PAYLOAD)
                .label("Payload")
                .description("The payload of the run as JSON.")
                .controlType(ControlType.TEXT_AREA)
                .defaultValue("{}")
                .required(false))
        .output(
            outputSchema(
                object()
                    .properties(
                        string("id")
                            .description("The id of the run."))))
        .help("", "https://docs.bytechef.io/reference/components/triggerDev_v1#trigger-task")
        .perform(TriggerDevTriggerTaskAction::perform);

    private TriggerDevTriggerTaskAction() {
    }

    public static Object perform(Parameters inputParameters, Parameters connectionParameters, Context context) {
        String payload = inputParameters.getString(PAYLOAD, "{}");

        return context
            .http(http -> http.post(
                "/api/v1/tasks/%s/trigger".formatted(inputParameters.getRequiredString(TASK_IDENTIFIER))))
            .body(Body.of("{\"payload\": %s}".formatted(payload), "application/json"))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody();
    }
}

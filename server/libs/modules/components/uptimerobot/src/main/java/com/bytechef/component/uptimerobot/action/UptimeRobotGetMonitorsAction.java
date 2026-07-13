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

package com.bytechef.component.uptimerobot.action;

import static com.bytechef.component.definition.ComponentDsl.action;
import static com.bytechef.component.definition.ComponentDsl.array;
import static com.bytechef.component.definition.ComponentDsl.integer;
import static com.bytechef.component.definition.ComponentDsl.object;
import static com.bytechef.component.definition.ComponentDsl.outputSchema;
import static com.bytechef.component.definition.ComponentDsl.string;
import static com.bytechef.component.definition.Context.Http.responseType;
import static com.bytechef.component.uptimerobot.constant.UptimeRobotConstants.API_KEY;
import static com.bytechef.component.uptimerobot.constant.UptimeRobotConstants.FORMAT;

import com.bytechef.component.definition.ComponentDsl.ModifiableActionDefinition;
import com.bytechef.component.definition.Context;
import com.bytechef.component.definition.Context.Http.Body;
import com.bytechef.component.definition.Context.Http.BodyContentType;
import com.bytechef.component.definition.Context.Http.ResponseType;
import com.bytechef.component.definition.Parameters;
import com.bytechef.component.definition.TypeReference;
import java.util.Map;

/**
 * @author Ivica Cardic
 */
public class UptimeRobotGetMonitorsAction {

    public static final ModifiableActionDefinition ACTION_DEFINITION = action("getMonitors")
        .title("Get Monitors")
        .description("Retrieves the monitors of the account together with their current statuses.")
        .output(
            outputSchema(
                object()
                    .properties(
                        string("stat")
                            .description("The status of the request."),
                        array("monitors")
                            .description("The monitors of the account.")
                            .items(
                                object()
                                    .properties(
                                        integer("id")
                                            .description("The id of the monitor."),
                                        string("friendly_name")
                                            .description("The friendly name of the monitor."),
                                        string("url")
                                            .description("The URL monitored by the monitor."),
                                        integer("type")
                                            .description("The type of the monitor."),
                                        integer("status")
                                            .description("The status of the monitor."))))))
        .help("", "https://docs.bytechef.io/reference/components/uptimerobot_v1#get-monitors")
        .perform(UptimeRobotGetMonitorsAction::perform);

    private UptimeRobotGetMonitorsAction() {
    }

    public static Map<String, Object> perform(
        Parameters inputParameters, Parameters connectionParameters, Context context) {

        return context.http(http -> http.post("/getMonitors"))
            .body(
                Body.of(
                    Map.of(
                        API_KEY, connectionParameters.getRequiredString(API_KEY),
                        FORMAT, "json"),
                    BodyContentType.FORM_URL_ENCODED))
            .configuration(responseType(ResponseType.JSON))
            .execute()
            .getBody(new TypeReference<>() {});
    }
}
